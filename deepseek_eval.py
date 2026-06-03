import os
import re
import json
import time
import argparse
import threading
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

import pandas as pd
from openai import OpenAI
from sklearn.metrics import accuracy_score, classification_report


ACTION_LABELS = {"ASK", "RETRIEVE", "ANSWER", "UNKNOWN"}
ROUTE_LABELS = {"DB", "WEB", "DB+WEB", "NA"}

_thread_local = threading.local()


def parse_args():
    parser = argparse.ArgumentParser(description="Evaluate scholar QA routing baselines with DeepSeek API")
    parser.add_argument("--input", type=str, required=True, help="Path to xlsx/csv dataset")
    parser.add_argument("--sheet", type=str, default="predictions", help="Sheet name if xlsx")
    parser.add_argument("--model", type=str, default="deepseek-chat")
    parser.add_argument("--task", type=str, choices=["action", "route", "both"], default="both")
    parser.add_argument(
        "--experiment",
        type=str,
        choices=["direct", "fewshot_structured", "cot_structured"],
        default="direct",
    )
    parser.add_argument("--temperature", type=float, default=0.0)
    parser.add_argument("--top_p", type=float, default=None)
    parser.add_argument("--max_tokens", type=int, default=800)
    parser.add_argument("--max_retries", type=int, default=10)
    parser.add_argument("--sleep", type=float, default=0.0)
    parser.add_argument("--output_dir", type=str, default="./deepseek_outputs")
    parser.add_argument("--num_threads", type=int, default=100)
    parser.add_argument("--api_key_env", type=str, default="DEEPSEEK_API_KEY")
    parser.add_argument("--base_url", type=str, default="https://api.deepseek.com")
    parser.add_argument("--no_json_mode", action="store_true")
    return parser.parse_args()


def load_table(path: str, sheet: str) -> pd.DataFrame:
    p = Path(path)
    if p.suffix.lower() == ".xlsx":
        return pd.read_excel(p, sheet_name=sheet)
    if p.suffix.lower() == ".csv":
        return pd.read_csv(p)
    raise ValueError("Only .xlsx and .csv are supported")


def build_client(args) -> OpenAI:
    api_key = os.environ.get(args.api_key_env)
    if not api_key:
        raise EnvironmentError(f"{args.api_key_env} is not set.")
    return OpenAI(api_key=api_key, base_url=args.base_url)


def get_client(args) -> OpenAI:
    if not hasattr(_thread_local, "client"):
        _thread_local.client = build_client(args)
    return _thread_local.client


def normalize_action_label(x) -> str:
    if pd.isna(x):
        return "UNKNOWN"
    x = str(x).strip().upper()
    if x in {"", "NAN", "NONE", "NULL"}:
        return "UNKNOWN"
    return x if x in ACTION_LABELS else "UNKNOWN"


def normalize_route_text(x) -> str:
    if pd.isna(x):
        return "NA"

    x = str(x).strip().upper()
    if x in {"", "NAN", "NONE", "NULL", "NO_ROUTE", "NO ROUTE"}:
        return "NA"

    x = (
        x.replace("DB + WEB", "DB+WEB")
        .replace("DB +WEB", "DB+WEB")
        .replace("DB+ WEB", "DB+WEB")
        .replace("WEB+DB", "DB+WEB")
        .replace("BOTH", "DB+WEB")
        .replace("MIXED", "DB+WEB")
        .replace("MIX", "DB+WEB")
        .replace("DATABASE", "DB")
        .replace("WEB_SEARCH", "WEB")
        .replace("WEB SEARCH", "WEB")
        .replace("NONE", "NA")
    )

    return x if x in ROUTE_LABELS else "NA"


def normalize_route_label(x, action_label: str) -> str:
    if action_label != "RETRIEVE":
        return "NA"
    return normalize_route_text(x)


def build_direct_system_prompt(task: str) -> str:
    if task == "action":
        return """你是学者目录系统的实验评测器。
你的任务是根据用户问题，只判断首动作 action_label。

可选标签只有四个：
1. ASK：当前信息不足，需要先追问再继续。
2. RETRIEVE：当前信息足够，应先检索。
3. ANSWER：当前无需检索，可直接回答。
4. UNKNOWN：当前无效、越界或无法确定。

输出要求：
- 只输出 JSON
- JSON 格式必须为：{"action_label":"ASK|RETRIEVE|ANSWER|UNKNOWN"}
- 不要输出解释
- 不要输出 markdown
"""

    if task == "route":
        return """你是学者目录系统的实验评测器。
你的任务是根据用户问题，只判断检索路由 route_label。

可选标签只有四个：
1. DB：仅查内部结构化学者数据库。
2. WEB：仅查外部网络。
3. DB+WEB：数据库与联网结合。
4. NA：当前不应检索，或无法判断。

输出要求：
- 只输出 JSON
- JSON 格式必须为：{"route_label":"DB|WEB|DB+WEB|NA"}
- 不要输出解释
- 不要输出 markdown
"""

    return """你是学者目录系统的实验评测器。
你的任务是根据用户问题，同时判断首动作 action_label 和检索路由 route_label。

动作标签：
- ASK：当前信息不足，需要先追问。
- RETRIEVE：当前应先检索。
- ANSWER：当前可直接回答。
- UNKNOWN：当前无效、越界或无法确定。

路由标签：
- DB：仅查内部结构化学者数据库。
- WEB：仅查外部网络。
- DB+WEB：数据库与联网结合。
- NA：当 action_label 不是 RETRIEVE 时必须为 NA。

输出要求：
- 只输出 JSON
- JSON 格式必须为：
{"action_label":"ASK|RETRIEVE|ANSWER|UNKNOWN","route_label":"DB|WEB|DB+WEB|NA"}
- 不要输出解释
- 不要输出 markdown
"""


def build_fewshot_system_prompt(task: str) -> str:
    common_definition = """你是智能学者目录系统的查询决策器。
请根据用户查询判断系统动作和检索路由。

动作标签只能是：
- ASK：信息不足或存在歧义，需要先追问。
- RETRIEVE：需要检索知识源后回答。
- ANSWER：无需检索，可直接回答。
- UNKNOWN：无效、越界或无法处理。

路由标签只能是：
- DB：仅调用内部结构化学者数据库。
- WEB：仅调用外部网络信息。
- DB+WEB：同时调用内部数据库和外部网络。
- NA：不进入检索流程；当 action_label 不是 RETRIEVE 时必须为 NA。
"""

    examples = """下面是代表性样例：

示例1：
用户查询：搜一下张伟的文章。
输出：{"action_label":"ASK","route_label":"NA"}

示例2：
用户查询：南京大学哪些学者研究智能目录？
输出：{"action_label":"RETRIEVE","route_label":"DB"}

示例3：
用户查询：某学者最近发表了哪些论文？
输出：{"action_label":"RETRIEVE","route_label":"WEB"}

示例4：
用户查询：结合已有成果和近期动态分析某学者研究方向变化。
输出：{"action_label":"RETRIEVE","route_label":"DB+WEB"}

示例5：
用户查询：帮我润色这段学者简介。
输出：{"action_label":"ANSWER","route_label":"NA"}

示例6：
用户查询：帮我监视某人的私人行踪。
输出：{"action_label":"UNKNOWN","route_label":"NA"}

示例7：
用户查询：他近两年有哪些新项目？
输出：{"action_label":"ASK","route_label":"NA"}

示例8：
用户查询：查询某学者的主要研究方向和代表成果。
输出：{"action_label":"RETRIEVE","route_label":"DB"}
"""

    if task == "action":
        return common_definition + examples + """
现在请只判断 action_label。
输出要求：
- 只输出 JSON
- JSON 格式必须为：{"action_label":"ASK|RETRIEVE|ANSWER|UNKNOWN"}
- 不要输出解释
- 不要输出 markdown
"""

    if task == "route":
        return common_definition + examples + """
现在请只判断 route_label。
输出要求：
- 只输出 JSON
- JSON 格式必须为：{"route_label":"DB|WEB|DB+WEB|NA"}
- 不要输出解释
- 不要输出 markdown
"""

    return common_definition + examples + """
现在请同时判断 action_label 和 route_label。
输出要求：
- 只输出 JSON
- JSON 格式必须为：
{"action_label":"ASK|RETRIEVE|ANSWER|UNKNOWN","route_label":"DB|WEB|DB+WEB|NA"}
- 不要输出解释
- 不要输出 markdown
"""


def build_cot_structured_system_prompt(task: str) -> str:
    common = """你是智能学者目录系统的结构化路由决策器。
你的任务是根据用户查询判断系统应执行 ASK、RETRIEVE、ANSWER 或 UNKNOWN，并在需要检索时判断 DB、WEB 或 DB+WEB。

重要要求：
- 只输出一个 JSON 对象。
- 不要输出 markdown。
- 不要输出自然语言解释。
- 不要输出长篇思维链。
- 只输出结构化中间字段和最终标签。
- 字段名必须完全符合要求。

动作标签 action_label：
- ASK：信息不足、实体不明确、重名、指代不明或缺少关键检索条件，需要先追问。
- RETRIEVE：需要检索知识源后回答。
- ANSWER：无需检索，可直接回答，如概念解释、写作建议、润色、改写、摘要、格式转换。
- UNKNOWN：无效、越界、隐私不当、无法处理，或不属于智能学者目录服务范围。

路由标签 route_label：
- DB：仅调用内部结构化学者数据库。适合学者基本信息、研究方向、论文成果、合作关系等稳定学术字段。
- WEB：仅调用外部网络信息。适合最新、近期、当前、今年、近两年、官网公告、主页动态、新闻、项目公示等时效信息。
- DB+WEB：同时需要内部数据库和外部网络。适合结合已有成果和近期动态进行综合分析。
- NA：不进入检索流程。当 action_label 不是 RETRIEVE 时，route_label 必须为 NA。

结构化判断字段：
- entity_status：clear | ambiguous | missing
- completeness_status：complete | partial | insufficient
- evidence_need：true | false
- freshness_need：true | false
- db_need：true | false
- web_need：true | false
- direct_answerable：true | false
- out_of_scope：true | false
- decision_basis：brief_text

决策规则：
1. 若 out_of_scope=true，则 action_label=UNKNOWN，route_label=NA。
2. 若目标实体 missing 或 ambiguous，且任务依赖具体学者、论文、机构或成果，则 action_label=ASK，route_label=NA。
3. 若 direct_answerable=true 且 evidence_need=false，则 action_label=ANSWER，route_label=NA。
4. 若 evidence_need=true 且信息充分，则 action_label=RETRIEVE。
5. 若 action_label 不是 RETRIEVE，则 route_label 必须为 NA。
6. 若 action_label=RETRIEVE 且 db_need=true、web_need=false，则 route_label=DB。
7. 若 action_label=RETRIEVE 且 db_need=false、web_need=true，则 route_label=WEB。
8. 若 action_label=RETRIEVE 且 db_need=true、web_need=true，则 route_label=DB+WEB。
9. 若出现 UNKNOWN、REFUSE、拒绝、越界等含义，最终统一输出 action_label=UNKNOWN。
10. 若出现 NONE、NO_ROUTE、不检索等含义，最终统一输出 route_label=NA。

典型样例：
用户查询：搜一下张伟的文章。
输出：
{"entity_status":"ambiguous","completeness_status":"insufficient","evidence_need":true,"freshness_need":false,"db_need":true,"web_need":false,"direct_answerable":false,"out_of_scope":false,"decision_basis":"姓名存在重名风险，需要补充机构或领域","action_label":"ASK","route_label":"NA"}

用户查询：南京大学哪些学者研究智能目录？
输出：
{"entity_status":"clear","completeness_status":"complete","evidence_need":true,"freshness_need":false,"db_need":true,"web_need":false,"direct_answerable":false,"out_of_scope":false,"decision_basis":"可由内部学者数据库检索","action_label":"RETRIEVE","route_label":"DB"}

用户查询：某学者最近两年发表了哪些论文？
输出：
{"entity_status":"clear","completeness_status":"complete","evidence_need":true,"freshness_need":true,"db_need":false,"web_need":true,"direct_answerable":false,"out_of_scope":false,"decision_basis":"涉及近期论文动态，需要外部信息","action_label":"RETRIEVE","route_label":"WEB"}

用户查询：结合已有成果和近期动态分析某学者研究方向变化。
输出：
{"entity_status":"clear","completeness_status":"complete","evidence_need":true,"freshness_need":true,"db_need":true,"web_need":true,"direct_answerable":false,"out_of_scope":false,"decision_basis":"同时需要内部画像和外部动态信息","action_label":"RETRIEVE","route_label":"DB+WEB"}

用户查询：帮我润色这段学者简介。
输出：
{"entity_status":"clear","completeness_status":"complete","evidence_need":false,"freshness_need":false,"db_need":false,"web_need":false,"direct_answerable":true,"out_of_scope":false,"decision_basis":"文本润色任务无需检索","action_label":"ANSWER","route_label":"NA"}

用户查询：帮我监视某人的私人行踪。
输出：
{"entity_status":"clear","completeness_status":"complete","evidence_need":false,"freshness_need":false,"db_need":false,"web_need":false,"direct_answerable":false,"out_of_scope":true,"decision_basis":"涉及不当隐私监视请求","action_label":"UNKNOWN","route_label":"NA"}
"""

    if task == "action":
        return common + """
本次只评价 action_label，但仍需输出结构化字段。
输出 JSON 格式：
{
  "entity_status":"clear|ambiguous|missing",
  "completeness_status":"complete|partial|insufficient",
  "evidence_need":true|false,
  "freshness_need":true|false,
  "db_need":true|false,
  "web_need":true|false,
  "direct_answerable":true|false,
  "out_of_scope":true|false,
  "decision_basis":"brief_text",
  "action_label":"ASK|RETRIEVE|ANSWER|UNKNOWN"
}
"""

    if task == "route":
        return common + """
本次只评价 route_label，但仍需输出结构化字段。
输出 JSON 格式：
{
  "entity_status":"clear|ambiguous|missing",
  "completeness_status":"complete|partial|insufficient",
  "evidence_need":true|false,
  "freshness_need":true|false,
  "db_need":true|false,
  "web_need":true|false,
  "direct_answerable":true|false,
  "out_of_scope":true|false,
  "decision_basis":"brief_text",
  "route_label":"DB|WEB|DB+WEB|NA"
}
"""

    return common + """
输出 JSON 格式：
{
  "entity_status":"clear|ambiguous|missing",
  "completeness_status":"complete|partial|insufficient",
  "evidence_need":true|false,
  "freshness_need":true|false,
  "db_need":true|false,
  "web_need":true|false,
  "direct_answerable":true|false,
  "out_of_scope":true|false,
  "decision_basis":"brief_text",
  "action_label":"ASK|RETRIEVE|ANSWER|UNKNOWN",
  "route_label":"DB|WEB|DB+WEB|NA"
}
"""


def build_system_prompt(task: str, experiment: str) -> str:
    if experiment == "direct":
        return build_direct_system_prompt(task)
    if experiment == "fewshot_structured":
        return build_fewshot_system_prompt(task)
    if experiment == "cot_structured":
        return build_cot_structured_system_prompt(task)
    raise ValueError(f"Unknown experiment: {experiment}")


def build_user_prompt(question: str, task: str, experiment: str) -> str:
    if experiment == "cot_structured":
        return f"用户查询：{question}\n请根据系统要求输出结构化 JSON。"

    if task == "action":
        return f"问题：{question}\n请输出 action_label 的 JSON。"
    if task == "route":
        return f"问题：{question}\n请输出 route_label 的 JSON。"
    return f"问题：{question}\n请同时输出 action_label 和 route_label 的 JSON。"


def safe_json_parse(text: str) -> dict:
    if text is None:
        raise ValueError("Empty response content")

    text = text.strip()

    try:
        return json.loads(text)
    except Exception:
        pass

    if "```" in text:
        cleaned = text.replace("```json", "").replace("```JSON", "").replace("```", "").strip()
        try:
            return json.loads(cleaned)
        except Exception:
            text = cleaned

    match = re.search(r"\{.*\}", text, flags=re.S)
    if match:
        try:
            return json.loads(match.group(0))
        except Exception:
            pass

    raise ValueError(f"Failed to parse JSON: {text}")


def normalize_pred_action(x):
    if x is None:
        return None

    v = str(x).strip().upper()

    if v in ACTION_LABELS:
        return v

    if "ASK" in v or "CLARIFY" in v or "追问" in v or "澄清" in v:
        return "ASK"

    if "RETRIEVE" in v or "SEARCH" in v or "检索" in v:
        return "RETRIEVE"

    if "ANSWER" in v or "DIRECT" in v or "直接回答" in v:
        return "ANSWER"

    if "UNKNOWN" in v or "REFUSE" in v or "拒绝" in v or "越界" in v or "无法处理" in v:
        return "UNKNOWN"

    return None


def normalize_pred_route(x):
    if x is None:
        return None

    v = str(x).strip().upper()

    v = (
        v.replace("DB + WEB", "DB+WEB")
        .replace("DB +WEB", "DB+WEB")
        .replace("DB+ WEB", "DB+WEB")
        .replace("WEB+DB", "DB+WEB")
        .replace("BOTH", "DB+WEB")
        .replace("MIXED", "DB+WEB")
        .replace("MIX", "DB+WEB")
        .replace("NONE", "NA")
        .replace("NO_ROUTE", "NA")
        .replace("NO ROUTE", "NA")
    )

    if v in ROUTE_LABELS:
        return v

    if "DB+WEB" in v or ("DB" in v and "WEB" in v):
        return "DB+WEB"

    if "WEB" in v or "联网" in v or "网络" in v:
        return "WEB"

    if "DB" in v or "DATABASE" in v or "本地" in v or "数据库" in v:
        return "DB"

    if "NA" in v or "NONE" in v or "不检索" in v:
        return "NA"

    return None


def extract_prediction(result: dict, task: str):
    action_raw = (
        result.get("action_label")
        or result.get("action")
        or result.get("Action")
        or result.get("ACTION")
    )

    route_raw = (
        result.get("route_label")
        or result.get("route")
        or result.get("Route")
        or result.get("ROUTE")
    )

    action_label = normalize_pred_action(action_raw)
    route_label = normalize_pred_route(route_raw)

    if task == "action":
        route_label = None

    elif task == "route":
        action_label = None

    else:
        if action_label != "RETRIEVE":
            route_label = "NA"
        else:
            if route_label is None or route_label == "NA":
                route_label = "DB"

    return action_label, route_label


def call_deepseek(args, system_prompt: str, user_prompt: str):
    client = get_client(args)
    last_err = None

    for attempt in range(args.max_retries):
        try:
            messages = [{"role": "system", "content": system_prompt}]

            if attempt > 0:
                messages.append({
                    "role": "system",
                    "content": (
                        "上一次输出为空、字段缺失或不是合法 JSON。"
                        "请严格只输出一个可解析 JSON 对象，"
                        "不要输出 markdown，不要输出解释，不要输出多余文本。"
                    ),
                })

            messages.append({"role": "user", "content": user_prompt})

            request_kwargs = {
                "model": args.model,
                "messages": messages,
                "temperature": args.temperature,
                "stream": False,
                "timeout": 60,
            }

            if args.top_p is not None:
                request_kwargs["top_p"] = args.top_p

            if args.max_tokens is not None and args.max_tokens > 0:
                request_kwargs["max_tokens"] = args.max_tokens

            if not args.no_json_mode:
                request_kwargs["response_format"] = {"type": "json_object"}

            response = client.chat.completions.create(**request_kwargs)
            content = response.choices[0].message.content
            obj = safe_json_parse(content)

            if not any(k in obj for k in ["action_label", "action", "route_label", "route"]):
                raise ValueError(f"JSON has no label fields: {obj}")

            return obj, response

        except Exception as e:
            last_err = e
            if attempt < args.max_retries - 1:
                time.sleep(2)

    raise last_err


def evaluate_action(df: pd.DataFrame):
    if "action_label" not in df.columns or "pred_action_label" not in df.columns:
        return None
    valid_df = df.dropna(subset=["action_label", "pred_action_label"]).copy()
    if len(valid_df) == 0:
        return None

    acc = accuracy_score(valid_df["action_label"], valid_df["pred_action_label"])
    report = classification_report(
        valid_df["action_label"],
        valid_df["pred_action_label"],
        labels=["ASK", "RETRIEVE", "ANSWER", "UNKNOWN"],
        digits=4,
        zero_division=0,
        output_dict=True,
    )
    return {"accuracy": acc, "report": report}


def evaluate_route(df: pd.DataFrame):
    if "route_label" not in df.columns or "pred_route_label" not in df.columns:
        return None

    valid_df = df[df["action_label"] == "RETRIEVE"].copy()
    valid_df = valid_df.dropna(subset=["route_label", "pred_route_label"])

    if len(valid_df) == 0:
        return None

    acc = accuracy_score(valid_df["route_label"], valid_df["pred_route_label"])
    report = classification_report(
        valid_df["route_label"],
        valid_df["pred_route_label"],
        labels=["DB", "WEB", "DB+WEB"],
        digits=4,
        zero_division=0,
        output_dict=True,
    )
    return {"accuracy": acc, "report": report}


def evaluate_joint_decision(df: pd.DataFrame):
    def check_row(row):
        gold_action = row["action_label"]
        pred_action = row["pred_action_label"]
        gold_route = row["route_label"]
        pred_route = row["pred_route_label"]

        if gold_action != "RETRIEVE":
            return int(pred_action == gold_action)

        return int((pred_action == "RETRIEVE") and (pred_route == gold_route))

    tmp = df.copy()
    tmp["joint_decision_correct"] = tmp.apply(check_row, axis=1)

    return {
        "joint_decision_accuracy": float(tmp["joint_decision_correct"].mean()),
        "n_samples": int(len(tmp)),
    }


def analyze_error_patterns(df: pd.DataFrame):
    err = {
        "should_answer_but_retrieve": 0,
        "should_ask_but_retrieve": 0,
        "should_retrieve_but_answer": 0,
        "should_retrieve_but_ask": 0,
        "should_dbweb_but_db": 0,
        "should_db_but_dbweb": 0,
        "should_web_but_db": 0,
        "should_db_but_web": 0,
        "gold_unknown_action": 0,
        "pred_unknown_action": 0,
        "action_mismatch": 0,
    }

    for _, row in df.iterrows():
        gold_action = row.get("action_label")
        pred_action = row.get("pred_action_label")
        gold_route = row.get("route_label")
        pred_route = row.get("pred_route_label")

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


def process_one_row(idx, row, args, system_prompt):
    question = str(row["question"]).strip()
    user_prompt = build_user_prompt(question, args.task, args.experiment)

    try:
        result, resp = call_deepseek(args=args, system_prompt=system_prompt, user_prompt=user_prompt)
        action_label, route_label = extract_prediction(result, args.task)

        usage = getattr(resp, "usage", None)
        usage_dict = {
            "prompt_tokens": getattr(usage, "prompt_tokens", None) if usage else None,
            "completion_tokens": getattr(usage, "completion_tokens", None) if usage else None,
            "total_tokens": getattr(usage, "total_tokens", None) if usage else None,
        }

        if args.sleep > 0:
            time.sleep(args.sleep)

        return idx, action_label, route_label, result, usage_dict

    except Exception as e:
        if args.sleep > 0:
            time.sleep(args.sleep)
        return idx, None, None, {"error": str(e)}, {
            "prompt_tokens": None,
            "completion_tokens": None,
            "total_tokens": None,
        }


def main():
    args = parse_args()
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    df = load_table(args.input, args.sheet).reset_index(drop=True)

    required_cols = {"id", "question", "action_label", "route_label"}
    missing = required_cols - set(df.columns)
    if missing:
        raise ValueError(f"Missing required columns: {missing}")

    df["question"] = df["question"].astype(str).str.strip()
    df["action_label"] = df["action_label"].apply(normalize_action_label)
    df["route_label"] = df.apply(
        lambda x: normalize_route_label(x["route_label"], x["action_label"]),
        axis=1,
    )

    system_prompt = build_system_prompt(args.task, args.experiment)

    n = len(df)
    pred_actions = [None] * n
    pred_routes = [None] * n
    raw_outputs = [None] * n
    raw_usage = [None] * n

    with ThreadPoolExecutor(max_workers=args.num_threads) as executor:
        futures = [
            executor.submit(process_one_row, idx, row, args, system_prompt)
            for idx, row in df.iterrows()
        ]

        finished = 0
        for future in as_completed(futures):
            idx, action_label, route_label, result, usage_dict = future.result()
            pred_actions[idx] = action_label
            pred_routes[idx] = route_label
            raw_outputs[idx] = result
            raw_usage[idx] = usage_dict

            finished += 1
            print(f"[{finished}/{n}] row={idx} done")

    if args.task in {"action", "both"}:
        df["pred_action_label"] = pred_actions
    else:
        df["pred_action_label"] = None

    if args.task in {"route", "both"}:
        df["pred_route_label"] = pred_routes
    else:
        df["pred_route_label"] = None

    if args.task == "both":
        df["pred_route_label"] = df.apply(
            lambda x: "NA" if x["pred_action_label"] != "RETRIEVE" else normalize_route_text(x["pred_route_label"]),
            axis=1,
        )

    if args.task == "route":
        df["pred_route_label"] = df["pred_route_label"].apply(normalize_route_text)

    df["raw_output"] = [json.dumps(x, ensure_ascii=False) for x in raw_outputs]
    df["raw_usage"] = [json.dumps(x, ensure_ascii=False) for x in raw_usage]

    file_prefix = f"{args.experiment}_{args.model}_{args.task}"

    pred_path = out_dir / f"{file_prefix}_predictions.xlsx"
    df.to_excel(pred_path, index=False)

    summary = {
        "experiment": args.experiment,
        "model": args.model,
        "task": args.task,
        "temperature": args.temperature,
        "top_p": args.top_p,
        "max_tokens": args.max_tokens,
        "n_samples": len(df),
        "num_threads": args.num_threads,
        "action_distribution": df["action_label"].value_counts().to_dict(),
        "route_distribution": df["route_label"].value_counts().to_dict(),
    }

    action_metrics = evaluate_action(df)
    route_metrics = evaluate_route(df)
    joint_metrics = None
    error_analysis = None

    if action_metrics is not None:
        summary["action_accuracy"] = action_metrics["accuracy"]
        summary["action_macro_f1"] = action_metrics["report"].get("macro avg", {}).get("f1-score")
        with open(out_dir / f"{file_prefix}_action_report.json", "w", encoding="utf-8") as f:
            json.dump(action_metrics["report"], f, ensure_ascii=False, indent=2)

    if route_metrics is not None:
        summary["route_accuracy"] = route_metrics["accuracy"]
        summary["route_macro_f1"] = route_metrics["report"].get("macro avg", {}).get("f1-score")
        with open(out_dir / f"{file_prefix}_route_report.json", "w", encoding="utf-8") as f:
            json.dump(route_metrics["report"], f, ensure_ascii=False, indent=2)

    if args.task == "both":
        joint_metrics = evaluate_joint_decision(df)
        error_analysis = analyze_error_patterns(df)

        with open(out_dir / f"{file_prefix}_joint_summary.json", "w", encoding="utf-8") as f:
            json.dump(joint_metrics, f, ensure_ascii=False, indent=2)

        with open(out_dir / f"{file_prefix}_error_analysis.json", "w", encoding="utf-8") as f:
            json.dump(error_analysis, f, ensure_ascii=False, indent=2)

        summary["joint_decision_accuracy"] = joint_metrics["joint_decision_accuracy"]
        summary["end_to_end_accuracy_deprecated"] = joint_metrics["joint_decision_accuracy"]

    with open(out_dir / f"{file_prefix}_summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print("Done.")
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    if error_analysis is not None:
        print("Error Analysis:")
        print(json.dumps(error_analysis, ensure_ascii=False, indent=2))

    print(f"Predictions saved to: {pred_path}")


if __name__ == "__main__":
    main()