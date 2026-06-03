from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import pandas as pd
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    precision_recall_fscore_support,
)


# ---------------------------
# 标签集合（支持 UNKNOWN / NA）
# ---------------------------

ACTION_LABELS = ["ASK", "ANSWER", "RETRIEVE", "UNKNOWN"]
ROUTE_LABELS = ["DB", "WEB", "DB+WEB", "NA"]


# ---------------------------
# 从当前 Java 规则提取并合并后的关键词
# ---------------------------

WRITING_WORDS = [
    "改写",
    "润色",
    "压缩",
    "提炼",
    "生成",
    "写一段",
    "一句话简介",
    "摘要",
    "通俗易懂",
    "口语化",
    "推荐语",
    "搜索提示词",
    "布尔查询式",
    "整理成",
    "翻译",
    "改成",
    "重写",
]

ANSWER_HINT_WORDS = [
    "什么是",
    "怎么理解",
    "理论流派",
    "研究方法",
    "方法论",
    "写作规范",
    "引用格式",
    "标题拟定",
    "致谢写法",
    "盲审",
    "投稿建议",
    "经验",
]

STRONG_TIME_WORDS = [
    "latest",
    "recent",
    "new",
    "current",
    "today",
    "yesterday",
    "this week",
    "near",
    "实时",
    "最新",
    "最近",
    "刚刚",
    "昨天",
    "今天",
    "本周",
    "近一年",
    "近1年",
    "2024年后",
    "近期",
    "近两年",
    "近三年",
    "近五年",
    "2024",
    "2025",
    "2026",
]

WEB_WORDS = [
    "主页",
    "官网",
    "任职",
    "职务",
    "联系方式",
    "讲座",
    "报告",
    "访谈",
    "新闻",
    "获奖",
    "项目",
    "基金",
    "实验室",
    "研究中心",
    "招募",
    "公告",
    "招聘",
    "社交媒体",
    "公众号",
]

FIELD_WORDS = ["scholar", "author", "学者", "作者", "领域", "代表作", "研究者"]
ARTICLE_WORDS = [
    "article",
    "paper",
    "文献",
    "论文",
    "文章",
    "检索",
    "搜索",
    "期刊",
    "cssci",
    "被引",
    "史料",
    "古籍",
    "档案",
]
THEORY_WORDS = ["理论", "观点", "框架", "流派", "分析", "诠释"]
STATS_WORDS = ["top10", "top 10", "rank", "ranking", "榜", "前十", "前10", "排行"]

DB_INTENT_WORDS = [
    "文献",
    "论文",
    "期刊",
    "被引",
    "cssci",
    "古籍",
    "档案",
    "author",
    "article",
    "paper",
]

VAGUE_REFER_WORDS = [
    "这个学者",
    "这个老师",
    "这个人",
    "他",
    "她",
    "这位老师",
    "这位学者",
]
COMPARE_WORDS = ["比较", "对比", "谁更", "哪个更", "差异", "区别"]
COMPARE_DIM_WORDS = ["方向", "论文", "成果", "合作", "主题", "近年", "近两年"]

PRIVACY_TARGET_WORDS = [
    "手机号",
    "手机号码",
    "住址",
    "家庭地址",
    "私人邮箱",
    "私人微信",
    "qq号",
    "通讯记录",
    "聊天记录",
    "未公开评审",
    "评审意见",
    "private phone",
    "private address",
    "private contact",
]
PRIVACY_INTENT_WORDS = [
    "私人",
    "私密",
    "未公开",
    "给我",
    "获取",
    "提供",
    "查一下",
    "tell me",
    "give me",
]

ILLEGAL_WORDS = [
    "监听",
    "监控",
    "入侵",
    "破解",
    "盗号",
    "伪造",
    "造假",
    "代写",
    "刷数据",
    "篡改数据",
    "规避irb",
    "forge",
    "hack",
    "fake irb",
    "fabricate",
    "plagiarize",
]

CAPABILITY_WORDS = [
    "登录系统帮我查",
    "帮我查投稿状态",
    "审稿系统状态",
    "访问我电脑",
    "读取我硬盘",
    "控制我的设备",
    "预测下期立项",
    "access my disk",
    "control my device",
    "check my review status",
]

AMBIGUOUS_NAMES = {
    "张伟",
    "王伟",
    "李强",
    "王磊",
    "刘洋",
    "李娜",
    "张敏",
    "王芳",
    "陈涛",
    "赵鹏",
    "李静",
    "孙涛",
    "周杰",
    "吴敏",
    "杨帆",
    "高翔",
    "王静",
    "李晨",
}
AMBIGUOUS_NAMES_SORTED = sorted(AMBIGUOUS_NAMES, key=len, reverse=True)

DISAMBIGUATION_HINTS = [
    "清华",
    "北大",
    "北京大学",
    "南京大学",
    "上海交大",
    "浙大",
    "复旦",
    "中科院",
    "方向",
    "领域",
    "研究",
    "论文",
    "老师",
    "学院",
    "学校",
    "单位",
    "机构",
]

EXPLICIT_VAGUE_RE = re.compile(
    r"^(他|她|ta|这个学者|那个人|这个人|thisscholar|thatauthor)(是谁|怎么样|是谁啊|如何|情况)?[?？!！。]*$",
    flags=re.IGNORECASE,
)
CHINESE_2_3_RE = re.compile(r"[\u4e00-\u9fff]{2,3}")


def normalize_text(text: str) -> str:
    return str(text).strip()


def normalize_for_match(text: str) -> str:
    return re.sub(r"\s+", "", str(text).strip().lower())


def normalize_action_label(x) -> str:
    if pd.isna(x):
        return "UNKNOWN"
    x = str(x).strip().upper()
    if x in {"", "NAN", "NONE", "NULL"}:
        return "UNKNOWN"
    if x not in ACTION_LABELS:
        return "UNKNOWN"
    return x


def normalize_route_label(x, action_label: str) -> str:
    # 所有非 RETRIEVE 的样本，路由统一设为 NA
    if action_label != "RETRIEVE":
        return "NA"

    if pd.isna(x):
        return "NA"

    x = str(x).strip().upper()
    if x in {"", "NAN", "NONE", "NULL"}:
        return "NA"

    x = (
        x.replace("DB + WEB", "DB+WEB")
        .replace("DB +WEB", "DB+WEB")
        .replace("DB+ WEB", "DB+WEB")
    )

    if x not in ROUTE_LABELS:
        return "NA"
    return x


def contains_any(text: str, keywords: list[str]) -> bool:
    return any(k in text for k in keywords)


def extract_ambiguous_name(text: str) -> Optional[str]:
    for name in AMBIGUOUS_NAMES_SORTED:
        if name in text:
            return name

    candidates = CHINESE_2_3_RE.findall(text)
    for cand in candidates:
        if cand in {"最近", "最新", "当前", "研究", "论文", "方向", "老师", "学者"}:
            continue
        if cand in AMBIGUOUS_NAMES:
            return cand
    return None


def has_disambiguation(text: str, name: Optional[str]) -> bool:
    if not name:
        return False
    remaining = text.replace(name, "")
    if contains_any(remaining, DISAMBIGUATION_HINTS):
        return True
    if "方向" in remaining or "领域" in remaining or "研究" in remaining:
        return True
    return False


def is_writing_task(text: str) -> bool:
    return contains_any(text, WRITING_WORDS)


def is_boundary_ask(text_raw: str, text_norm: str) -> bool:
    if contains_any(text_raw, PRIVACY_TARGET_WORDS) and contains_any(
        text_raw, PRIVACY_INTENT_WORDS
    ):
        return True
    if contains_any(text_raw, ILLEGAL_WORDS):
        return True
    if contains_any(text_raw, CAPABILITY_WORDS):
        return True
    if len(text_norm) <= 24 and EXPLICIT_VAGUE_RE.fullmatch(text_norm):
        return True
    return False


@dataclass
class QuerySignals:
    strong_time: bool
    web_signal: bool
    db_intent: bool
    ask_field: bool
    ask_article: bool
    ask_theory: bool
    ask_stats: bool
    structured_retrieve: bool
    web_only_retrieve: bool


def detect_signals(question: str) -> QuerySignals:
    q_raw = normalize_text(question)
    q = normalize_for_match(question)

    strong_time = contains_any(q, STRONG_TIME_WORDS)
    web_signal = contains_any(q, WEB_WORDS)
    db_intent = contains_any(q, DB_INTENT_WORDS)

    ask_field = contains_any(q, FIELD_WORDS)
    ask_article = contains_any(q, ARTICLE_WORDS)
    ask_theory = contains_any(q, THEORY_WORDS)
    ask_stats = contains_any(q, STATS_WORDS)

    if strong_time and ask_theory:
        ask_article = True

    structured_retrieve = ask_field or ask_article or ask_stats or ask_theory
    web_only_retrieve = strong_time and (not db_intent) and (not structured_retrieve)

    return QuerySignals(
        strong_time=strong_time,
        web_signal=web_signal,
        db_intent=db_intent,
        ask_field=ask_field,
        ask_article=ask_article,
        ask_theory=ask_theory,
        ask_stats=ask_stats,
        structured_retrieve=structured_retrieve,
        web_only_retrieve=web_only_retrieve,
    )


def is_vague_query(text: str) -> bool:
    q_raw = normalize_text(text)
    q = normalize_for_match(text)

    if contains_any(q, VAGUE_REFER_WORDS):
        return True

    name = extract_ambiguous_name(q_raw)
    if name and not has_disambiguation(q_raw, name):
        if (
            q_raw.startswith("介绍一下")
            or q_raw.startswith("帮我找")
            or q_raw.startswith("看看")
        ):
            return True

    if contains_any(q, COMPARE_WORDS) and not contains_any(q, COMPARE_DIM_WORDS):
        return True

    return False


def is_answer_task(text: str, signals: QuerySignals) -> bool:
    q = normalize_for_match(text)
    if is_writing_task(q):
        return True
    if (
        contains_any(q, ANSWER_HINT_WORDS)
        and not signals.structured_retrieve
        and not signals.web_only_retrieve
    ):
        return True
    return False


def rule_action(question: str) -> str:
    q_raw = normalize_text(question)
    q = normalize_for_match(question)
    signals = detect_signals(q_raw)

    if is_boundary_ask(q, q):
        return "ASK"
    if is_vague_query(q_raw):
        return "ASK"

    if is_answer_task(q_raw, signals):
        return "ANSWER"

    if signals.web_only_retrieve or signals.structured_retrieve:
        return "RETRIEVE"

    if contains_any(q, ["查", "找", "检索", "搜索", "有哪些", "列出", "帮我查"]):
        return "RETRIEVE"

    # 兜底策略：无法判断时记为 UNKNOWN
    return "UNKNOWN"


def rule_route(question: str) -> str:
    q_raw = normalize_text(question)
    q = normalize_for_match(question)
    s = detect_signals(q_raw)

    if s.web_only_retrieve:
        return "WEB"

    if s.structured_retrieve:
        if s.strong_time:
            return "DB+WEB"
        return "DB"

    if s.web_signal and s.strong_time:
        return "WEB"
    if s.strong_time:
        return "DB+WEB"
    if s.web_signal and not s.db_intent:
        return "WEB"
    return "DB"


def safe_route_predict(question: str, pred_action: str) -> str:
    if pred_action != "RETRIEVE":
        return "NA"
    return rule_route(question)


def build_summary_from_report(report: dict, labels: list[str]) -> dict:
    out = {
        "accuracy": report["accuracy"],
        "macro_precision": report["macro avg"]["precision"],
        "macro_recall": report["macro avg"]["recall"],
        "macro_f1": report["macro avg"]["f1-score"],
    }
    for label in labels:
        if label in report:
            out[f"{label}_precision"] = report[label]["precision"]
            out[f"{label}_recall"] = report[label]["recall"]
            out[f"{label}_f1"] = report[label]["f1-score"]
            out[f"{label}_support"] = report[label]["support"]
    return out


def add_micro_avg_if_missing(report: dict, labels: list[str]) -> dict:
    """
    When `macro avg` exists but `micro avg` is absent, add micro metrics.
    For single-label multi-class settings, micro precision/recall/f1 equal accuracy.
    """
    if "macro avg" not in report or "micro avg" in report or "accuracy" not in report:
        return report

    support = float(sum(report[label]["support"] for label in labels if label in report))
    micro = {
        "precision": float(report["accuracy"]),
        "recall": float(report["accuracy"]),
        "f1-score": float(report["accuracy"]),
        "support": support,
    }
    report = dict(report)
    report["micro avg"] = micro
    return report


def format_action_report_for_ours(report: dict) -> dict:
    formatted = {
        "accuracy": float(report["accuracy"]),
        "ASK": report["ASK"],
        "RETRIEVE": report["RETRIEVE"],
        "ANSWER": report["ANSWER"],
        "UNKNOWN": report["UNKNOWN"],
    }
    if "micro avg" in report:
        formatted["micro avg"] = report["micro avg"]
    formatted["macro avg"] = report["macro avg"]
    formatted["weighted avg"] = report["weighted avg"]
    return formatted


def format_route_report_for_ours(report: dict, accuracy: float) -> dict:
    support = float(
        sum(report[label]["support"] for label in ["DB", "WEB", "DB+WEB"] if label in report)
    )
    micro_avg = {
        "precision": float(accuracy),
        "recall": float(accuracy),
        "f1-score": float(accuracy),
        "support": support,
    }
    return {
        "accuracy": float(accuracy),
        "DB": report["DB"],
        "WEB": report["WEB"],
        "DB+WEB": report["DB+WEB"],
        "micro avg": micro_avg,
        "macro avg": report["macro avg"],
        "weighted avg": report["weighted avg"],
    }


def evaluate_action(df: pd.DataFrame, output_dir: Path) -> dict:
    print("\n=== Action Evaluation ===")
    acc = accuracy_score(df["action_label"], df["pred_action_rule"])
    report = classification_report(
        df["action_label"],
        df["pred_action_rule"],
        labels=ACTION_LABELS,
        digits=4,
        zero_division=0,
        output_dict=True,
    )
    report = add_micro_avg_if_missing(report, ACTION_LABELS)

    print(f"Action Accuracy: {acc:.4f}")
    print(
        classification_report(
            df["action_label"],
            df["pred_action_rule"],
            labels=ACTION_LABELS,
            digits=4,
            zero_division=0,
        )
    )

    action_report = format_action_report_for_ours(report)
    with open(output_dir / "action_report.json", "w", encoding="utf-8") as f:
        json.dump(action_report, f, ensure_ascii=False, indent=2)

    return build_summary_from_report(action_report, ACTION_LABELS)


def evaluate_route_oracle(df: pd.DataFrame, output_dir: Path) -> dict:
    route_df = df[df["action_label"] == "RETRIEVE"].copy()
    print("\n=== Oracle Route Evaluation ===")

    if route_df.empty:
        print("No RETRIEVE samples found.")
        return {}

    route_df["pred_route_rule_oracle"] = route_df["question"].apply(rule_route)
    acc = accuracy_score(route_df["route_label"], route_df["pred_route_rule_oracle"])
    report = classification_report(
        route_df["route_label"],
        route_df["pred_route_rule_oracle"],
        labels=["DB", "WEB", "DB+WEB"],
        digits=4,
        zero_division=0,
        output_dict=True,
    )
    report = add_micro_avg_if_missing(report, ["DB", "WEB", "DB+WEB"])

    print(f"Route Accuracy (Oracle): {acc:.4f}")
    print(
        classification_report(
            route_df["route_label"],
            route_df["pred_route_rule_oracle"],
            labels=["DB", "WEB", "DB+WEB"],
            digits=4,
            zero_division=0,
        )
    )

    route_report = format_route_report_for_ours(report, acc)

    with open(output_dir / "route_report.json", "w", encoding="utf-8") as f:
        json.dump(route_report, f, ensure_ascii=False, indent=2)

    return build_summary_from_report(route_report, ["DB", "WEB", "DB+WEB"])


def evaluate_route_end2end(df: pd.DataFrame, output_dir: Path) -> dict:
    route_df = df[df["action_label"] == "RETRIEVE"].copy()
    print("\n=== End-to-End Route Evaluation ===")

    if route_df.empty:
        print("No RETRIEVE samples found.")
        return {}

    route_df["pred_route_rule_e2e"] = route_df.apply(
        lambda x: x["pred_route_rule"] if x["pred_action_rule"] == "RETRIEVE" else "NA",
        axis=1,
    )
    route_df["route_correct_e2e"] = (
        route_df["pred_route_rule_e2e"] == route_df["route_label"]
    ).astype(int)

    acc = route_df["route_correct_e2e"].mean()
    print(f"Route Accuracy (End-to-End on gold RETRIEVE): {acc:.4f}")

    valid_mask = route_df["pred_route_rule_e2e"] != "NA"
    if valid_mask.any():
        print("\nClassification report on predicted RETRIEVE subset:")
        report = classification_report(
            route_df.loc[valid_mask, "route_label"],
            route_df.loc[valid_mask, "pred_route_rule_e2e"],
            labels=["DB", "WEB", "DB+WEB"],
            digits=4,
            zero_division=0,
            output_dict=True,
        )
        report = add_micro_avg_if_missing(report, ["DB", "WEB", "DB+WEB"])
        print(
            classification_report(
                route_df.loc[valid_mask, "route_label"],
                route_df.loc[valid_mask, "pred_route_rule_e2e"],
                labels=["DB", "WEB", "DB+WEB"],
                digits=4,
                zero_division=0,
            )
        )
    else:
        print(
            "No samples were predicted as RETRIEVE, so no route classification report is available."
        )
        report = {}

    with open(output_dir / "route_end2end_report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    summary = {"route_end2end_accuracy": float(acc)}
    if report:
        summary.update(build_summary_from_report(report, ["DB", "WEB", "DB+WEB"]))
    return summary


def evaluate_end_to_end(df: pd.DataFrame) -> dict:
    """
    端到端定义：
    - gold action != RETRIEVE：只要求 action 正确
    - gold action == RETRIEVE：要求 action 正确 且 route 正确
    """

    def check_row(row):
        if row["action_label"] != "RETRIEVE":
            return int(row["pred_action_rule"] == row["action_label"])
        return int(
            (row["pred_action_rule"] == "RETRIEVE")
            and (row["pred_route_rule"] == row["route_label"])
        )

    df = df.copy()
    df["end_to_end_correct"] = df.apply(check_row, axis=1)
    return {
        "end_to_end_accuracy": float(df["end_to_end_correct"].mean()),
        "n_samples": int(len(df)),
    }


def attach_error_type(df: pd.DataFrame) -> pd.DataFrame:
    def infer_error(row):
        gold_a = row["action_label"]
        pred_a = row["pred_action_rule"]
        gold_r = row["route_label"]
        pred_r = row["pred_route_rule"]

        if gold_a != pred_a:
            if gold_a == "ASK" and pred_a == "RETRIEVE":
                return "should_ask_but_retrieve"
            if gold_a == "RETRIEVE" and pred_a == "ASK":
                return "should_retrieve_but_ask"
            if gold_a == "ANSWER" and pred_a == "RETRIEVE":
                return "should_answer_but_retrieve"
            if gold_a == "RETRIEVE" and pred_a == "ANSWER":
                return "should_retrieve_but_answer"
            if gold_a == "UNKNOWN":
                return "gold_unknown_action"
            if pred_a == "UNKNOWN":
                return "pred_unknown_action"
            return "action_mismatch"

        if gold_a == "RETRIEVE" and gold_r != pred_r:
            if gold_r == "DB+WEB" and pred_r == "DB":
                return "should_dbweb_but_db"
            if gold_r == "DB+WEB" and pred_r == "WEB":
                return "should_dbweb_but_web"
            if gold_r == "WEB" and pred_r == "DB":
                return "should_web_but_db"
            if gold_r == "DB" and pred_r == "WEB":
                return "should_db_but_web"
            if gold_r == "DB" and pred_r == "DB+WEB":
                return "should_db_but_dbweb"
            if gold_r == "WEB" and pred_r == "DB+WEB":
                return "should_web_but_dbweb"
            return "route_mismatch"

        return ""

    df["error_type"] = df.apply(infer_error, axis=1)
    return df


def summarize_error_types(df: pd.DataFrame) -> dict:
    err_df = df[df["error_type"] != ""].copy()
    if err_df.empty:
        return {}
    return err_df["error_type"].value_counts().to_dict()


def main():
    parser = argparse.ArgumentParser(
        description="Rule-based evaluation for scholar QA dataset."
    )
    parser.add_argument(
        "--input",
        type=str,
        default="D:\\Java Projects\\cssci\\src\\test\\java\\com\\nju\\cssci\\scholar_dataset_10000_v3.xlsx",
        help="Path to xlsx file",
    )
    parser.add_argument("--sheet", type=str, default="predictions", help="Sheet name")
    parser.add_argument(
        "--output-dir", type=str, default="rule_eval_outputs", help="Output directory"
    )
    args = parser.parse_args()

    input_path = Path(args.input)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    df = pd.read_excel(input_path, sheet_name=args.sheet)

    required_cols = {"id", "question", "action_label", "route_label"}
    missing = required_cols - set(df.columns)
    if missing:
        raise ValueError(f"Missing required columns: {missing}")

    df["question"] = df["question"].astype(str)
    df["action_label"] = df["action_label"].apply(normalize_action_label)
    df["route_label"] = df.apply(
        lambda x: normalize_route_label(x["route_label"], x["action_label"]),
        axis=1,
    )

    df["pred_action_rule"] = df["question"].apply(rule_action)
    df["pred_route_rule"] = df.apply(
        lambda x: safe_route_predict(x["question"], x["pred_action_rule"]),
        axis=1,
    )

    df["action_correct"] = (df["pred_action_rule"] == df["action_label"]).astype(int)

    df["route_correct_oracle"] = df.apply(
        lambda x: (
            int(rule_route(x["question"]) == x["route_label"])
            if x["action_label"] == "RETRIEVE"
            else pd.NA
        ),
        axis=1,
    )

    df["route_correct_e2e"] = df.apply(
        lambda x: (
            int(x["pred_route_rule"] == x["route_label"])
            if x["action_label"] == "RETRIEVE" and x["pred_action_rule"] == "RETRIEVE"
            else (0 if x["action_label"] == "RETRIEVE" else pd.NA)
        ),
        axis=1,
    )

    df = attach_error_type(df)

    action_summary = evaluate_action(df, output_dir)
    route_oracle_summary = evaluate_route_oracle(df, output_dir)
    route_end2end_summary = evaluate_route_end2end(df, output_dir)
    e2e_summary = evaluate_end_to_end(df)
    error_summary = summarize_error_types(df)

    detailed_path = output_dir / "rule_eval_detailed.xlsx"
    errors_path = output_dir / "rule_eval_errors.xlsx"
    csv_path = output_dir / "rule_eval_detailed.csv"

    df.to_excel(detailed_path, index=False)
    df.to_csv(csv_path, index=False, encoding="utf-8-sig")

    error_df = df[(df["action_correct"] == 0) | (df["error_type"] != "")]
    error_df.to_excel(errors_path, index=False)

    summary = {
        "model": "rule-based",
        "task": "both",
        "n_samples": int(len(df)),
        "service_fail_count": 0,
        "action_macro_f1": float(action_summary.get("macro_f1", 0.0)),
        "action_ask_recall": float(action_summary.get("ASK_recall", 0.0)),
        "route_macro_f1": float(route_oracle_summary.get("macro_f1", 0.0)),
        "route_db_web_recall": float(route_oracle_summary.get("DB+WEB_recall", 0.0)),
        "route_web_recall": float(route_oracle_summary.get("WEB_recall", 0.0)),
        "end_to_end_accuracy": float(e2e_summary["end_to_end_accuracy"]),
    }

    with open(output_dir / "summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print("\n=== Summary Saved ===")
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    print("\n=== Files Saved ===")
    print(f"Detailed results: {detailed_path}")
    print(f"Detailed CSV:     {csv_path}")
    print(f"Error cases:      {errors_path}")
    print(f"Summary JSON:     {output_dir / 'summary.json'}")


if __name__ == "__main__":
    main()
