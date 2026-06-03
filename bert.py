import os
import json
import random
import argparse
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from sklearn.metrics import accuracy_score, precision_recall_fscore_support, classification_report

from datasets import Dataset, DatasetDict
from transformers import (
    AutoTokenizer,
    AutoModelForSequenceClassification,
    DataCollatorWithPadding,
    Trainer,
    TrainingArguments,
    set_seed,
)


ACTION_LABELS = ["ASK", "RETRIEVE", "ANSWER", "UNKNOWN"]
ROUTE_LABELS = ["DB", "WEB", "DB+WEB"]
ALL_ROUTE_LABELS = ["DB", "WEB", "DB+WEB", "NA"]


def parse_args():
    parser = argparse.ArgumentParser(description="BERT two-stage training for scholar QA")
    parser.add_argument("--input", type=str, required=True, help="Path to xlsx/csv dataset")
    parser.add_argument("--sheet", type=str, default="dataset_v2", help="Sheet name if xlsx")
    parser.add_argument(
        "--models",
        type=str,
        required=True,
        help="Comma-separated model names, e.g. bert-base-chinese,hfl/chinese-roberta-wwm-ext"
    )
    parser.add_argument(
        "--stage",
        type=str,
        choices=["action", "route", "both"],
        default="both",
        help="Train action classifier, route classifier, or both"
    )
    parser.add_argument("--text_col", type=str, default="question")
    parser.add_argument("--action_col", type=str, default="action_label")
    parser.add_argument("--route_col", type=str, default="route_label")
    parser.add_argument("--output_dir", type=str, default="./bert_two_stage_outputs")
    parser.add_argument("--split_cache_dir", type=str, default="./split_cache", help="Dir to save/load split files")
    parser.add_argument("--max_length", type=int, default=128)
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--batch_size", type=int, default=16)
    parser.add_argument("--lr", type=float, default=2e-5)
    parser.add_argument("--weight_decay", type=float, default=0.01)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--test_size", type=float, default=0.2)
    parser.add_argument("--dev_size", type=float, default=0.1)
    parser.add_argument("--use_fp16", action="store_true")
    return parser.parse_args()


def load_table(path: str, sheet: str) -> pd.DataFrame:
    path = Path(path)
    if path.suffix.lower() == ".xlsx":
        return pd.read_excel(path, sheet_name=sheet)
    if path.suffix.lower() == ".csv":
        return pd.read_csv(path)
    raise ValueError("Only .xlsx and .csv are supported")


def validate_columns(df: pd.DataFrame, cols):
    missing = [c for c in cols if c not in df.columns]
    if missing:
        raise ValueError(f"Missing columns: {missing}")


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
    # 所有非 RETRIEVE 样本，route 统一视为 NA
    if action_label != "RETRIEVE":
        return "NA"

    if pd.isna(x):
        return "NA"

    x = str(x).strip().upper()
    if x in {"", "NAN", "NONE", "NULL"}:
        return "NA"

    x = x.replace("DB + WEB", "DB+WEB").replace("DB +WEB", "DB+WEB").replace("DB+ WEB", "DB+WEB")
    if x not in ALL_ROUTE_LABELS:
        return "NA"
    return x


def build_label_maps(labels):
    label2id = {label: i for i, label in enumerate(labels)}
    id2label = {i: label for label, i in label2id.items()}
    return label2id, id2label


def to_hf_dataset(df: pd.DataFrame, text_col: str, label_col: str, label2id: dict):
    tmp = df[[text_col, label_col]].copy()
    tmp = tmp.rename(columns={text_col: "text", label_col: "label"})
    tmp["label"] = tmp["label"].map(label2id)
    return Dataset.from_pandas(tmp, preserve_index=False)


def compute_metrics_factory(id2label):
    def compute_metrics(eval_pred):
        logits, labels = eval_pred
        preds = np.argmax(logits, axis=-1)

        acc = accuracy_score(labels, preds)
        precision_macro, recall_macro, f1_macro, _ = precision_recall_fscore_support(
            labels, preds, average="macro", zero_division=0
        )

        result = {
            "accuracy": acc,
            "precision_macro": precision_macro,
            "recall_macro": recall_macro,
            "f1_macro": f1_macro,
        }

        p, r, f1, support = precision_recall_fscore_support(
            labels, preds, labels=list(id2label.keys()), zero_division=0
        )
        for i, label_name in id2label.items():
            safe_name = label_name.replace("+", "PLUS").replace(" ", "_")
            result[f"{safe_name}_precision"] = p[i]
            result[f"{safe_name}_recall"] = r[i]
            result[f"{safe_name}_f1"] = f1[i]
            result[f"{safe_name}_support"] = support[i]

        return result

    return compute_metrics


def tokenize_function_factory(tokenizer, max_length):
    def tokenize_function(batch):
        return tokenizer(
            batch["text"],
            truncation=True,
            max_length=max_length,
        )
    return tokenize_function


def save_classification_report(trainer, dataset, output_path, id2label):
    preds_output = trainer.predict(dataset)
    logits = preds_output.predictions
    labels = preds_output.label_ids
    preds = np.argmax(logits, axis=-1)

    y_true = [id2label[int(x)] for x in labels]
    y_pred = [id2label[int(x)] for x in preds]

    report = classification_report(y_true, y_pred, digits=4, zero_division=0, output_dict=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)


def get_predictions_df(trainer, raw_df, hf_dataset, id2label, label_col):
    preds_output = trainer.predict(hf_dataset)
    logits = preds_output.predictions
    preds = np.argmax(logits, axis=-1)

    out_df = raw_df.copy().reset_index(drop=True)
    out_df[f"pred_{label_col}"] = [id2label[int(x)] for x in preds]
    out_df[f"{label_col}_correct"] = (out_df[label_col] == out_df[f"pred_{label_col}"]).astype(int)
    return out_df


def save_predictions_df(df, output_path):
    df.to_excel(output_path, index=False)


def show_distribution(name, df, label_col):
    print(f"\n{name} - {label_col}")
    print(df[label_col].value_counts(dropna=False).sort_index())


def assert_all_labels_present(df, label_col, expected_labels, split_name):
    existing = set(df[label_col].dropna().unique().tolist())
    missing = set(expected_labels) - existing
    if missing:
        raise ValueError(f"{split_name} 缺少类别: {missing}")


def check_label_distribution(df, label_col, expected_labels, task_name, min_count=3):
    counts = df[label_col].value_counts().to_dict()
    print(f"\n[DEBUG] {task_name} 原始分布: {counts}")

    missing = [x for x in expected_labels if x not in counts]
    if missing:
        raise ValueError(f"{task_name} 原始数据缺少类别: {missing}")

    too_small = [x for x in expected_labels if counts.get(x, 0) < min_count]
    if too_small:
        raise ValueError(f"{task_name} 中以下类别样本过少(<{min_count})，无法稳定切分: {too_small}")


def per_class_split(df: pd.DataFrame, label_col: str, test_ratio=0.2, dev_ratio=0.1, seed=42):
    train_list = []
    dev_list = []
    test_list = []

    for label in sorted(df[label_col].unique()):
        sub_df = df[df[label_col] == label].sample(frac=1, random_state=seed).reset_index(drop=True)
        n = len(sub_df)

        n_test = int(round(n * test_ratio))
        n_dev = int(round(n * dev_ratio))

        if n_test < 1:
            n_test = 1
        if n_dev < 1:
            n_dev = 1

        if n - n_test - n_dev < 1:
            overflow = 1 - (n - n_test - n_dev)
            if n_test >= n_dev and n_test > 1:
                n_test -= overflow
            else:
                n_dev -= overflow

        n_train = n - n_test - n_dev

        train_part = sub_df.iloc[:n_train]
        dev_part = sub_df.iloc[n_train:n_train + n_dev]
        test_part = sub_df.iloc[n_train + n_dev:]

        train_list.append(train_part)
        dev_list.append(dev_part)
        test_list.append(test_part)

        print(f"[DEBUG] {label}: train={len(train_part)}, dev={len(dev_part)}, test={len(test_part)}")

    train_df = pd.concat(train_list).sample(frac=1, random_state=seed).reset_index(drop=True)
    dev_df = pd.concat(dev_list).sample(frac=1, random_state=seed).reset_index(drop=True)
    test_df = pd.concat(test_list).sample(frac=1, random_state=seed).reset_index(drop=True)

    return train_df, dev_df, test_df


def build_route_subset(df: pd.DataFrame, action_col: str, route_col: str) -> pd.DataFrame:
    route_df = df[df[action_col] == "RETRIEVE"].copy()
    route_df = route_df[route_df[route_col].isin(ROUTE_LABELS)].copy()
    return route_df


def get_split_paths(split_cache_dir: Path, task_name: str):
    task_dir = split_cache_dir / task_name
    task_dir.mkdir(parents=True, exist_ok=True)
    return {
        "train": task_dir / "train.xlsx",
        "dev": task_dir / "dev.xlsx",
        "test": task_dir / "test.xlsx",
    }


def load_or_create_action_splits(df: pd.DataFrame, text_col: str, action_col: str, route_col: str,
                                 split_cache_dir: Path, test_size: float, dev_size: float, seed: int):
    paths = get_split_paths(split_cache_dir, "action")

    if all(p.exists() for p in paths.values()):
        print("\n[INFO] 检测到已有 action 切分文件，直接读取。")
        train_df = pd.read_excel(paths["train"])
        dev_df = pd.read_excel(paths["dev"])
        test_df = pd.read_excel(paths["test"])
    else:
        print("\n[INFO] 未检测到 action 切分文件，开始自动切分并保存。")
        action_df = df[[text_col, action_col, route_col]].copy()
        action_df = action_df[action_df[action_col].isin(ACTION_LABELS)].copy()

        check_label_distribution(action_df, action_col, ACTION_LABELS, "action")
        train_df, dev_df, test_df = per_class_split(
            action_df,
            label_col=action_col,
            test_ratio=test_size,
            dev_ratio=dev_size,
            seed=seed
        )

        assert_all_labels_present(train_df, action_col, ACTION_LABELS, "action_train")
        assert_all_labels_present(dev_df, action_col, ACTION_LABELS, "action_dev")
        assert_all_labels_present(test_df, action_col, ACTION_LABELS, "action_test")

        train_df.to_excel(paths["train"], index=False)
        dev_df.to_excel(paths["dev"], index=False)
        test_df.to_excel(paths["test"], index=False)

    show_distribution("action_train", train_df, action_col)
    show_distribution("action_dev", dev_df, action_col)
    show_distribution("action_test", test_df, action_col)

    return train_df, dev_df, test_df


def load_or_create_route_splits(df: pd.DataFrame, text_col: str, action_col: str, route_col: str,
                                split_cache_dir: Path, test_size: float, dev_size: float, seed: int):
    paths = get_split_paths(split_cache_dir, "route")

    if all(p.exists() for p in paths.values()):
        print("\n[INFO] 检测到已有 route 切分文件，直接读取。")
        train_df = pd.read_excel(paths["train"])
        dev_df = pd.read_excel(paths["dev"])
        test_df = pd.read_excel(paths["test"])
    else:
        print("\n[INFO] 未检测到 route 切分文件，开始自动切分并保存。")
        route_df = build_route_subset(df, action_col, route_col)
        route_df = route_df[[text_col, route_col]].copy()

        check_label_distribution(route_df, route_col, ROUTE_LABELS, "route")
        train_df, dev_df, test_df = per_class_split(
            route_df,
            label_col=route_col,
            test_ratio=test_size,
            dev_ratio=dev_size,
            seed=seed
        )

        assert_all_labels_present(train_df, route_col, ROUTE_LABELS, "route_train")
        assert_all_labels_present(dev_df, route_col, ROUTE_LABELS, "route_dev")
        assert_all_labels_present(test_df, route_col, ROUTE_LABELS, "route_test")

        train_df.to_excel(paths["train"], index=False)
        dev_df.to_excel(paths["dev"], index=False)
        test_df.to_excel(paths["test"], index=False)

    show_distribution("route_train", train_df, route_col)
    show_distribution("route_dev", dev_df, route_col)
    show_distribution("route_test", test_df, route_col)

    return train_df, dev_df, test_df


def build_full_test_df(df: pd.DataFrame, text_col: str, action_col: str, route_col: str,
                       test_size: float, dev_size: float, seed: int):
    full_df = df[[text_col, action_col, route_col]].copy()
    full_df = full_df[full_df[action_col].isin(ACTION_LABELS)].copy()

    _, _, test_df = per_class_split(
        full_df,
        label_col=action_col,
        test_ratio=test_size,
        dev_ratio=dev_size,
        seed=seed
    )
    return test_df.reset_index(drop=True)


def train_single_task(
    model_name: str,
    task_name: str,
    train_df: pd.DataFrame,
    dev_df: pd.DataFrame,
    test_df: pd.DataFrame,
    text_col: str,
    label_col: str,
    labels: list,
    output_root: Path,
    max_length: int,
    epochs: int,
    batch_size: int,
    lr: float,
    weight_decay: float,
    seed: int,
    use_fp16: bool,
):
    print(f"\n========== {task_name.upper()} | {model_name} ==========")

    label2id, id2label = build_label_maps(labels)

    tokenizer = AutoTokenizer.from_pretrained(model_name, use_fast=True)
    model = AutoModelForSequenceClassification.from_pretrained(
        model_name,
        num_labels=len(labels),
        label2id=label2id,
        id2label=id2label,
    )

    train_ds = to_hf_dataset(train_df, text_col, label_col, label2id)
    dev_ds = to_hf_dataset(dev_df, text_col, label_col, label2id)
    test_ds = to_hf_dataset(test_df, text_col, label_col, label2id)

    ds_dict = DatasetDict({"train": train_ds, "dev": dev_ds, "test": test_ds})

    tokenize_fn = tokenize_function_factory(tokenizer, max_length)
    tokenized_ds = ds_dict.map(tokenize_fn, batched=True)

    data_collator = DataCollatorWithPadding(tokenizer=tokenizer)

    safe_model_name = model_name.replace("/", "__")
    task_output_dir = output_root / task_name / safe_model_name
    task_output_dir.mkdir(parents=True, exist_ok=True)

    training_args = TrainingArguments(
        output_dir=str(task_output_dir / "checkpoints"),
        eval_strategy="epoch",
        save_strategy="epoch",
        logging_strategy="epoch",
        learning_rate=lr,
        per_device_train_batch_size=batch_size,
        per_device_eval_batch_size=batch_size,
        num_train_epochs=epochs,
        weight_decay=weight_decay,
        load_best_model_at_end=True,
        metric_for_best_model="f1_macro",
        greater_is_better=True,
        save_total_limit=1,
        seed=seed,
        fp16=use_fp16,
        report_to="none",
    )

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized_ds["train"],
        eval_dataset=tokenized_ds["dev"],
        tokenizer=tokenizer,
        data_collator=data_collator,
        compute_metrics=compute_metrics_factory(id2label),
    )

    trainer.train()

    dev_metrics = trainer.evaluate(tokenized_ds["dev"])
    test_metrics = trainer.evaluate(tokenized_ds["test"])

    with open(task_output_dir / "dev_metrics.json", "w", encoding="utf-8") as f:
        json.dump(dev_metrics, f, ensure_ascii=False, indent=2)

    with open(task_output_dir / "test_metrics.json", "w", encoding="utf-8") as f:
        json.dump(test_metrics, f, ensure_ascii=False, indent=2)

    save_classification_report(
        trainer,
        tokenized_ds["test"],
        task_output_dir / "test_classification_report.json",
        id2label,
    )

    pred_df = get_predictions_df(
        trainer,
        test_df,
        tokenized_ds["test"],
        id2label,
        label_col=label_col
    )

    save_predictions_df(pred_df, task_output_dir / "test_predictions.xlsx")

    print("Dev metrics:", dev_metrics)
    print("Test metrics:", test_metrics)

    return {
        "model_name": model_name,
        "task_name": task_name,
        "dev_metrics": dev_metrics,
        "test_metrics": test_metrics,
        "task_output_dir": str(task_output_dir),
        "pred_df": pred_df,
    }


def evaluate_end_to_end(full_test_df: pd.DataFrame,
                        action_pred_df: pd.DataFrame,
                        route_pred_df: pd.DataFrame,
                        text_col: str,
                        action_col: str,
                        route_col: str):
    """
    端到端定义：
    - 若 gold action != RETRIEVE，只要求 pred_action 正确
    - 若 gold action == RETRIEVE，要求 pred_action 正确 且 pred_route 正确
    """
    merged = full_test_df.copy().reset_index(drop=True)

    merged = merged.merge(
        action_pred_df[[text_col, f"pred_{action_col}", f"{action_col}_correct"]],
        on=text_col,
        how="left"
    )

    merged[f"pred_{route_col}"] = "NA"
    merged[f"{route_col}_correct"] = None

    retrieve_gold_df = merged[merged[action_col] == "RETRIEVE"].copy()

    if len(retrieve_gold_df) > 0:
        route_merge_df = retrieve_gold_df[[text_col, route_col]].merge(
            route_pred_df[[text_col, route_col, f"pred_{route_col}", f"{route_col}_correct"]],
            on=[text_col, route_col],
            how="left"
        )

        merged.loc[merged[action_col] == "RETRIEVE", f"pred_{route_col}"] = route_merge_df[f"pred_{route_col}"].values
        merged.loc[merged[action_col] == "RETRIEVE", f"{route_col}_correct"] = route_merge_df[f"{route_col}_correct"].values

    merged["end_to_end_correct"] = 0

    non_retrieve_mask = merged[action_col] != "RETRIEVE"
    merged.loc[non_retrieve_mask, "end_to_end_correct"] = (
        merged.loc[non_retrieve_mask, f"{action_col}_correct"]
        .fillna(0)
        .astype(int)
    )

    retrieve_mask = merged[action_col] == "RETRIEVE"
    merged.loc[retrieve_mask, "end_to_end_correct"] = (
        merged.loc[retrieve_mask, f"{action_col}_correct"].fillna(0).astype(int)
        &
        merged.loc[retrieve_mask, f"{route_col}_correct"].fillna(0).astype(int)
    ).astype(int)

    e2e_acc = merged["end_to_end_correct"].mean()

    return {
        "end_to_end_accuracy": float(e2e_acc),
        "n_samples": int(len(merged)),
    }, merged


def analyze_error_patterns(merged_df: pd.DataFrame, action_col: str, route_col: str):
    err = {
        "should_answer_but_retrieve": 0,
        "should_ask_but_retrieve": 0,
        "should_retrieve_but_answer": 0,
        "should_retrieve_but_ask": 0,
        "should_dbweb_but_db": 0,
        "should_db_but_dbweb": 0,
        "should_web_but_db": 0,
        "should_db_but_web": 0,
        "action_mismatch": 0,
        "gold_unknown_action": 0,
        "pred_unknown_action": 0,
    }

    pred_action_col = f"pred_{action_col}"
    pred_route_col = f"pred_{route_col}"

    for _, row in merged_df.iterrows():
        gold_action = row[action_col]
        pred_action = row[pred_action_col]
        gold_route = row.get(route_col, None)
        pred_route = row.get(pred_route_col, None)

        if gold_action != pred_action:
            err["action_mismatch"] += 1

        if gold_action == "UNKNOWN":
            err["gold_unknown_action"] += 1
        if pred_action == "UNKNOWN":
            err["pred_unknown_action"] += 1

        if gold_action == "ANSWER" and pred_action == "RETRIEVE":
            err["should_answer_but_retrieve"] += 1

        if gold_action == "ASK" and pred_action == "RETRIEVE":
            err["should_ask_but_retrieve"] += 1

        if gold_action == "RETRIEVE" and pred_action == "ANSWER":
            err["should_retrieve_but_answer"] += 1

        if gold_action == "RETRIEVE" and pred_action == "ASK":
            err["should_retrieve_but_ask"] += 1

        if gold_action == "RETRIEVE" and pred_action == "RETRIEVE":
            if gold_route == "DB+WEB" and pred_route == "DB":
                err["should_dbweb_but_db"] += 1
            if gold_route == "DB" and pred_route == "DB+WEB":
                err["should_db_but_dbweb"] += 1
            if gold_route == "WEB" and pred_route == "DB":
                err["should_web_but_db"] += 1
            if gold_route == "DB" and pred_route == "WEB":
                err["should_db_but_web"] += 1

    return err


def main():
    args = parse_args()
    set_seed(args.seed)
    random.seed(args.seed)
    np.random.seed(args.seed)

    output_root = Path(args.output_dir)
    output_root.mkdir(parents=True, exist_ok=True)

    split_cache_dir = Path(args.split_cache_dir)
    split_cache_dir.mkdir(parents=True, exist_ok=True)

    df = load_table(args.input, args.sheet)
    validate_columns(df, [args.text_col, args.action_col, args.route_col])

    df = df[[args.text_col, args.action_col, args.route_col]].copy()
    df = df.dropna(subset=[args.text_col]).copy()

    df[args.text_col] = df[args.text_col].astype(str).str.strip()
    df[args.action_col] = df[args.action_col].apply(normalize_action_label)
    df[args.route_col] = df.apply(
        lambda x: normalize_route_label(x[args.route_col], x[args.action_col]),
        axis=1,
    )

    df = df[df[args.action_col].isin(ACTION_LABELS)].copy()

    print("\n[DEBUG] 当前读取数据量:", len(df))
    print("[DEBUG] action 分布:")
    print(df[args.action_col].value_counts(dropna=False))

    route_df_debug = df[df[args.action_col] == "RETRIEVE"].copy()
    print("\n[DEBUG] route 分布 (gold RETRIEVE only):")
    print(route_df_debug[args.route_col].value_counts(dropna=False))

    models = [m.strip() for m in args.models.split(",") if m.strip()]
    all_results = []

    action_splits = None
    route_splits = None
    full_test_df = None

    if args.stage in ["action", "both"]:
        action_splits = load_or_create_action_splits(
            df=df,
            text_col=args.text_col,
            action_col=args.action_col,
            route_col=args.route_col,
            split_cache_dir=split_cache_dir,
            test_size=args.test_size,
            dev_size=args.dev_size,
            seed=args.seed,
        )

    if args.stage in ["route", "both"]:
        route_splits = load_or_create_route_splits(
            df=df,
            text_col=args.text_col,
            action_col=args.action_col,
            route_col=args.route_col,
            split_cache_dir=split_cache_dir,
            test_size=args.test_size,
            dev_size=args.dev_size,
            seed=args.seed,
        )

    if args.stage == "both":
        full_test_df = build_full_test_df(
            df=df,
            text_col=args.text_col,
            action_col=args.action_col,
            route_col=args.route_col,
            test_size=args.test_size,
            dev_size=args.dev_size,
            seed=args.seed,
        )

    for model_name in models:
        action_result = None
        route_result = None

        if args.stage in ["action", "both"]:
            train_df, dev_df, test_df = action_splits
            action_result = train_single_task(
                model_name=model_name,
                task_name="action",
                train_df=train_df,
                dev_df=dev_df,
                test_df=test_df,
                text_col=args.text_col,
                label_col=args.action_col,
                labels=ACTION_LABELS,
                output_root=output_root,
                max_length=args.max_length,
                epochs=args.epochs,
                batch_size=args.batch_size,
                lr=args.lr,
                weight_decay=args.weight_decay,
                seed=args.seed,
                use_fp16=args.use_fp16,
            )
            all_results.append({
                "model_name": model_name,
                "task_name": "action",
                "dev_metrics": action_result["dev_metrics"],
                "test_metrics": action_result["test_metrics"],
                "task_output_dir": action_result["task_output_dir"],
            })

        if args.stage in ["route", "both"]:
            train_df, dev_df, test_df = route_splits
            route_result = train_single_task(
                model_name=model_name,
                task_name="route",
                train_df=train_df,
                dev_df=dev_df,
                test_df=test_df,
                text_col=args.text_col,
                label_col=args.route_col,
                labels=ROUTE_LABELS,
                output_root=output_root,
                max_length=args.max_length,
                epochs=args.epochs,
                batch_size=args.batch_size,
                lr=args.lr,
                weight_decay=args.weight_decay,
                seed=args.seed,
                use_fp16=args.use_fp16,
            )
            all_results.append({
                "model_name": model_name,
                "task_name": "route",
                "dev_metrics": route_result["dev_metrics"],
                "test_metrics": route_result["test_metrics"],
                "task_output_dir": route_result["task_output_dir"],
            })

        if args.stage == "both" and action_result is not None and route_result is not None:
            e2e_summary, merged_df = evaluate_end_to_end(
                full_test_df=full_test_df,
                action_pred_df=action_result["pred_df"],
                route_pred_df=route_result["pred_df"],
                text_col=args.text_col,
                action_col=args.action_col,
                route_col=args.route_col,
            )

            error_analysis = analyze_error_patterns(
                merged_df=merged_df,
                action_col=args.action_col,
                route_col=args.route_col,
            )

            safe_model_name = model_name.replace("/", "__")
            combined_dir = output_root / "combined" / safe_model_name
            combined_dir.mkdir(parents=True, exist_ok=True)

            with open(combined_dir / "end_to_end_summary.json", "w", encoding="utf-8") as f:
                json.dump(e2e_summary, f, ensure_ascii=False, indent=2)

            with open(combined_dir / "error_analysis.json", "w", encoding="utf-8") as f:
                json.dump(error_analysis, f, ensure_ascii=False, indent=2)

            merged_df.to_excel(combined_dir / "combined_predictions.xlsx", index=False)

            all_results.append({
                "model_name": model_name,
                "task_name": "combined",
                "end_to_end_summary": e2e_summary,
                "error_analysis": error_analysis,
                "task_output_dir": str(combined_dir),
            })

            print(f"\n[COMBINED] {model_name}")
            print("End-to-End Summary:", e2e_summary)
            print("Error Analysis:", error_analysis)

    with open(output_root / "all_results.json", "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2)

    print(f"\n所有结果已保存到: {output_root}")
    print(f"切分缓存目录: {split_cache_dir}")


if __name__ == "__main__":
    main()