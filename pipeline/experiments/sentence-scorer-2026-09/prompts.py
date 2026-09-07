"""Which prompt makes the local model best at spotting unidiomatic Chinese?

Scoring the whole corpus is a one-time, irreversible pass over every example sentence the
app ships, so the prompt is worth choosing on evidence rather than taste.

**Variants are compared on the DEV half only.** The TEST half is scored once, by the winner,
in confirm.py — with ten variants on 200 sentences, picking the best on the same data you
report would be measuring luck. Few-shot examples come from bank.json, which is disjoint
from both halves, so no variant is shown a sentence it will be graded on.

Run:  cd pipeline && PYTHONPATH=. .venv/bin/python experiments/sentence-scorer-2026-09/prompts.py
"""
from __future__ import annotations

import json
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

HERE = Path(__file__).resolve().parent
D = HERE.parents[1] / "data" / "derived" / "scorer-validation"
MODEL = "qwen3.5:4b"
WORKERS = 2

RUBRIC_ZH = "（5=完全自然地道，4=自然，3=别扭，2=有语法或搭配错误，1=不通顺）"

# What actually goes wrong in this corpus, from reading ~2,400 of its sentences: it is
# machine- and second-language-translated, so the failures are collocation and calque rather
# than the tone/particle errors a learner makes.
RULES_ZH = """判断标准：
1. 搭配是否得当（例如「减弱碰击地面」「花费信仰」「拥护能源需求」都是错误搭配）。
2. 是否有语法错误：成分残缺或多余、重复的字、错别字（「源在」应为「源于」、「的的」、「即是」应为「既是」）。
3. 语序是否是中文语序，而不是照搬英文（例如把「在……的时候」「通过……」放到句末）。
4. 是否是直译腔：字面翻译英文习语而在中文里不成立。
5. 量词、介词、连词是否用对。
注意：繁体字、台湾或香港用词、书面语、专业术语、粗俗内容都不算错误。"""

RULES_EN = """Judging criteria:
1. Collocation: do the words actually go together in Chinese?
2. Grammar: missing or duplicated elements, wrong characters.
3. Word order: is it Chinese order, or English order transliterated?
4. Calque: a literal rendering of an English idiom that does not work in Chinese.
5. Correct measure words, prepositions and conjunctions.
Traditional characters, Taiwanese or Hong Kong usage, formal register, technical terms and
crude content are NOT errors."""


def examples(bank: dict, n_bad: int, n_good: int) -> str:
    rows = ([(s, 2) for s in bank["bad"][:n_bad]] + [(s, 5) for s in bank["good"][:n_good]])
    rows.sort(key=lambda r: r[0])
    return "例子：\n" + "\n".join(f"{s} → {v}" for s, v in rows)


def build_variants(bank: dict) -> dict:
    base_zh = f"你是中文母语者。给这句话的自然度打分，只输出一个数字{RUBRIC_ZH}，不要输出别的。\n句子："
    return {
        "A_baseline_zh": dict(prompt=lambda s: base_zh + s),
        "B_english": dict(prompt=lambda s: (
            "You are a native Chinese speaker. Rate how natural and idiomatic this Chinese "
            "sentence is. Output one digit only (5=completely natural, 4=natural, 3=awkward, "
            "2=grammar or collocation error, 1=incoherent). Nothing else.\nSentence: " + s)),
        "C_rules_zh": dict(prompt=lambda s: (
            f"你是中文母语者。给这句话的自然度打分{RUBRIC_ZH}。\n{RULES_ZH}\n"
            f"只输出一个数字，不要输出别的。\n句子：" + s)),
        "D_fewshot_zh": dict(prompt=lambda s: (
            f"你是中文母语者。给这句话的自然度打分{RUBRIC_ZH}。\n{examples(bank, 8, 8)}\n"
            f"只输出一个数字，不要输出别的。\n句子：" + s)),
        "E_rules_fewshot": dict(prompt=lambda s: (
            f"你是中文母语者。给这句话的自然度打分{RUBRIC_ZH}。\n{RULES_ZH}\n{examples(bank, 8, 8)}\n"
            f"只输出一个数字，不要输出别的。\n句子：" + s)),
        "F_manyshot_40": dict(prompt=lambda s: (
            f"你是中文母语者。给这句话的自然度打分{RUBRIC_ZH}。\n{examples(bank, 40, 40)}\n"
            f"只输出一个数字，不要输出别的。\n句子：" + s)),
        "K_manyshot_all": dict(prompt=lambda s: (
            f"你是中文母语者。给这句话的自然度打分{RUBRIC_ZH}。\n{examples(bank, 67, 67)}\n"
            f"只输出一个数字，不要输出别的。\n句子：" + s)),
        "L_rules_manyshot": dict(prompt=lambda s: (
            f"你是中文母语者。给这句话的自然度打分{RUBRIC_ZH}。\n{RULES_ZH}\n{examples(bank, 40, 40)}\n"
            f"只输出一个数字，不要输出别的。\n句子：" + s)),
        "G_rules_english": dict(prompt=lambda s: (
            f"You are a native Chinese speaker. Rate this Chinese sentence 1-5 for how "
            f"natural it is (5=completely natural, 1=incoherent).\n{RULES_EN}\n"
            f"Output one digit only.\nSentence: " + s)),
        "H_binary": dict(prompt=lambda s: (
            "你是中文母语者。下面这句话，中文母语者会不会这样说？"
            "只回答一个字：会 或 不会。\n句子：" + s), binary=True),
        "I_native_would_write": dict(prompt=lambda s: (
            f"你是中文报刊的校对编辑。这句话需要修改吗？"
            f"打分{RUBRIC_ZH}，5=完全不用改，1=必须重写。只输出一个数字。\n句子：" + s)),
        "J_thinking": dict(prompt=lambda s: (
            f"你是中文母语者。先想清楚这句话有没有搭配、语法、语序或直译腔的问题，"
            f"然后给自然度打分{RUBRIC_ZH}。最后只输出一个数字。\n句子：" + s), think=True),
    }


def call(prompt: str, think: bool, npred: int) -> str:
    body = json.dumps({"model": MODEL, "prompt": prompt, "stream": False, "think": think,
                       "options": {"temperature": 0, "num_predict": npred}}).encode()
    req = urllib.request.Request("http://localhost:11434/api/generate", body,
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        d = json.loads(r.read())
    return (d.get("response") or "") or (d.get("thinking") or "")


def score(text: str, spec: dict) -> int | None:
    think = spec.get("think", False)
    raw = call(spec["prompt"](text), think, 400 if think else (4 if spec.get("binary") else 3))
    if spec.get("binary"):
        return 5 if "不会" not in raw and "会" in raw else 2
    tail = raw.strip()[-40:] if think else raw
    digit = next((c for c in reversed(tail) if c.isdigit()) if think
                 else (c for c in tail if c.isdigit()), None)
    return int(digit) if digit and "1" <= digit <= "5" else None


def metrics(bad: list[int], good: list[int]) -> dict:
    out = {"mean_bad": round(sum(bad) / len(bad), 2), "mean_good": round(sum(good) / len(good), 2),
           "thresholds": {}}
    best = {"recall_at_p95": 0.0, "cut": None}
    for cut in (1, 2, 3, 4):
        tp = sum(1 for s in bad if s <= cut)
        fp = sum(1 for s in good if s <= cut)
        p = tp / (tp + fp) if tp + fp else None
        r = tp / len(bad)
        out["thresholds"][cut] = {"precision": round(p, 3) if p is not None else None,
                                  "recall": round(r, 3), "good_deleted": fp}
        # The product question: how many defects can we remove while deleting <=5% of good?
        if p is not None and p >= 0.95 and r > best["recall_at_p95"]:
            best = {"recall_at_p95": round(r, 3), "cut": cut}
    out.update(best)
    return out


def main() -> None:
    split = json.loads((D / "split.json").read_text(encoding="utf-8"))["dev"]
    bank = json.loads((D / "bank.json").read_text(encoding="utf-8"))
    variants = build_variants(bank)
    cache_path = D / "prompt-variants.jsonl"
    cache = {}
    if cache_path.exists():
        for line in cache_path.read_text(encoding="utf-8").splitlines():
            try:
                r = json.loads(line)
                cache[(r["v"], r["k"])] = r["s"]
            except (json.JSONDecodeError, KeyError):
                continue

    results = {}
    with cache_path.open("a", encoding="utf-8") as out:
        for name, spec in variants.items():
            t0 = time.time()
            todo = [t for t in split["bad"] + split["good"] if (name, t) not in cache]
            if todo:
                with ThreadPoolExecutor(WORKERS) as ex:
                    for text, s in zip(todo, ex.map(lambda t: score(t, spec), todo)):
                        if s is None:
                            continue
                        cache[(name, text)] = s
                        out.write(json.dumps({"v": name, "k": text, "s": s},
                                             ensure_ascii=False) + "\n")
                        out.flush()
            b = [cache[(name, t)] for t in split["bad"] if (name, t) in cache]
            g = [cache[(name, t)] for t in split["good"] if (name, t) in cache]
            m = metrics(b, g)
            m["seconds_per_sentence"] = round((time.time() - t0) / max(len(todo), 1), 2) if todo else None
            m["n_scored"] = len(b) + len(g)
            results[name] = m
            print(f"{name:<22} recall@P95={m['recall_at_p95']:<6} cut={m['cut']} "
                  f"mean_bad={m['mean_bad']} mean_good={m['mean_good']}", flush=True)

    (HERE / "prompt-results.json").write_text(
        json.dumps(results, indent=2, ensure_ascii=False) + "\n")
    ranked = sorted(results.items(), key=lambda kv: -kv[1]["recall_at_p95"])
    print("\nranked on DEV by recall at >=95% precision:")
    for n, m in ranked:
        print(f"  {m['recall_at_p95']:<6} {n:<22} (cut<={m['cut']}, "
              f"{m['thresholds'][m['cut']]['good_deleted'] if m['cut'] else '-'} good deleted)")


if __name__ == "__main__":
    main()
