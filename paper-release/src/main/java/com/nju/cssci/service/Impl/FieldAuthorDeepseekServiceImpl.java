package com.nju.cssci.service.Impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nju.cssci.service.FieldAuthorDeepseekService;
import com.nju.cssci.experiment.ExperimentLlmAttempt;
import com.nju.cssci.experiment.ExperimentProperties;
import com.nju.cssci.experiment.LlmStage;
import com.nju.cssci.experiment.PromptTemplateHashRegistry;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class FieldAuthorDeepseekServiceImpl implements FieldAuthorDeepseekService {

    private static final Logger logger = LoggerFactory.getLogger(FieldAuthorDeepseekServiceImpl.class);

    @Value("${deepseek.api.url:https://api.deepseek.com/chat/completions}")
    private String apiUrl;

    @Value("${deepseek.api.key:}")
    private String apiKey;

    @Value("${deepseek.model:deepseek-chat}")
    private String model;

    @Value("${deepseek.timeout.connect-ms:5000}")
    private Integer connectTimeoutMs;

    @Value("${deepseek.timeout.read-ms:15000}")
    private Integer readTimeoutMs;

    private final ExperimentProperties experimentProperties;

    public FieldAuthorDeepseekServiceImpl(ExperimentProperties experimentProperties,
            PromptTemplateHashRegistry promptTemplateHashRegistry) {
        this.experimentProperties = experimentProperties;
        promptTemplateHashRegistry.register(LlmStage.SCHOLAR_PARAM_PARSER.name(), buildSystemPrompt());
    }

    @Override
    public String requestFieldAuthorJsonResult(String prompt) {
        return requestFieldAuthorJsonResult(prompt, 1, 1);
    }

    @Override
    public String requestFieldAuthorJsonResult(String prompt, int attempt, int maxAttempts) {
        if (StringUtils.isBlank(apiKey)) {
            throw new IllegalStateException("deepseek.api.key is empty");
        }
        RestTemplate restTemplate = buildRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.1);

        String systemPrompt = buildSystemPrompt();
        JSONArray messages = new JSONArray();
        messages.add(buildMessage("system", systemPrompt));
        messages.add(buildMessage("user", prompt));
        body.put("messages", messages);

        JSONObject responseFormat = new JSONObject();
        responseFormat.put("type", "json_object");
        body.put("response_format", responseFormat);

        String actualPayload = body.toJSONString();
        ExperimentLlmAttempt observation = ExperimentLlmAttempt.start(LlmStage.SCHOLAR_PARAM_PARSER,
                "scholar_param_parser", model, apiUrl, attempt, maxAttempts, 0.1D, null,
                experimentProperties.getParserVersion(), LlmStage.SCHOLAR_PARAM_PARSER.name(),
                systemPrompt, actualPayload);
        JSONObject root = null;
        try {
            HttpEntity<String> entity = new HttpEntity<>(actualPayload, headers);
            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, String.class);
            String rawResponse = response.getBody();
            if (StringUtils.isBlank(rawResponse)) {
                throw new IllegalStateException("deepseek response is empty");
            }
            root = JSON.parseObject(rawResponse);
            String content = extractAssistantContent(root, rawResponse);
            observation.success(root);
            return content;
        } catch (RuntimeException ex) {
            observation.failure(ex, root);
            throw ex;
        }
    }

    private JSONObject buildMessage(String role, String content) {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private RestTemplate buildRestTemplate() {
        int safeConnectTimeout = connectTimeoutMs == null || connectTimeoutMs <= 0 ? 5000 : connectTimeoutMs;
        int safeReadTimeout = readTimeoutMs == null || readTimeoutMs <= 0 ? 15000 : readTimeoutMs;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(safeConnectTimeout);
        factory.setReadTimeout(safeReadTimeout);
        return new RestTemplate(factory);
    }

    private String extractAssistantContent(JSONObject root, String rawResponse) {
        JSONArray choices = root.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            logger.error("deepseek raw response: {}", rawResponse);
            throw new IllegalStateException("deepseek choices is empty");
        }
        JSONObject first = choices.getJSONObject(0);
        if (first == null) {
            throw new IllegalStateException("deepseek first choice is null");
        }
        JSONObject message = first.getJSONObject("message");
        if (message == null) {
            throw new IllegalStateException("deepseek message is null");
        }
        String content = message.getString("content");
        if (StringUtils.isBlank(content)) {
            throw new IllegalStateException("deepseek message content is empty");
        }
        return content;
    }

    private String buildSystemPrompt() {
        return """
                你是 CSSCI 人文社会科学“领域学者检索”的结构化参数生成器。系统会先按照文献条件召回论文，再按作者聚合得到领域学者。你必须把用户需求转换为一个可直接执行的 JSON 对象。

                【适用性判断：最高优先级】
                在抽取参数前，必须先判断输入是否表达了“检索某研究领域学者”的意图。只有用户希望依据研究主题、论文主题、CSSCI 二级学科、机构、作者姓名或年份查找和排序学者时，才生成检索条件。
                - 属于领域学者检索：找研究某主题或学科的学者、查某机构相关领域学者、按论文数/被引量/S-H-P指数排序学者。
                - 可能属于但信息不足：只说“帮我找学者”或只给姓名但没有研究领域，应保留可靠条件并要求补充研究主题；完全没有可靠条件时使用空条件。
                - 明显不属于领域学者检索：寒暄闲聊、天气时间、生活建议、娱乐请求、代码编写、文本创作，以及普通概念问答、文献内容总结、论文润色或只想检索论文而不是学者。
                对明显不属于或完全无法形成领域条件的输入，必须拒绝参数化：conditions=[]，sortBy="relevance"，pageIndex=1，pageSize=20，yearStart=null，yearEnd=null，isAccurate=false，strictMode=false，expandMode=true，scanBatchSize=300，maxScanRounds=20，needClarification=true；clarificationQuestions 给出 1 至 2 条具体引导，例如请用户提供研究主题、CSSCI二级学科、机构或年份。不得把原句、寒暄内容或非学术任务硬塞进 keyword/title 等字段。

                【内部分析要求】
                请先在内部判断是否属于领域学者检索；不属于时立即按上述拒绝规则输出，不再抽取条件。属于时再依次识别检索领域、作者姓名、机构、年份、逻辑关系、精确程度和聚合模式，并校验全部参数。信息不足时只保留用户明确表达的可靠条件，禁止臆造研究主题。不得输出分析过程、思维链、解释或中间结果。

                【唯一允许的输出结构】
                {
                  "rewrittenQuery": string,
                  "conditions": [
                    {
                      "logic": "and|or|not",
                      "field": "title|author|keyword|unit|subject",
                      "value": string,
                      "accurate": boolean
                    }
                  ],
                  "sortBy": "cited|paperNumber|score|hscore|pscore|year|relevance",
                  "pageIndex": number,
                  "pageSize": number,
                  "yearStart": number|null,
                  "yearEnd": number|null,
                  "isAccurate": boolean,
                  "strictMode": boolean,
                  "expandMode": boolean,
                  "scanBatchSize": number,
                  "maxScanRounds": number,
                  "needClarification": boolean,
                  "clarificationQuestions": string[]
                }

                【字段与执行规则】
                - title：论文标题；author：最终聚合后的学者姓名过滤；keyword：研究主题或关键词；unit：作者机构；subject：CSSCI 二级学科名称或 xkfl 分类代码。
                - 不允许 journal 等结构之外的检索字段。用户未指定字段的研究方向通常映射为 keyword。
                - 有效的领域学者检索至少要包含一个 title、keyword、unit 或 subject 条件；只有 author 条件时应请求用户补充研究领域。
                - logic：“并且/同时”为 and，“或者/任一”为 or，“排除/不含”为 not；首个正向条件使用 and。
                - 用户明确要求“精确、完全一致、仅限”时，相应条件 accurate=true，否则为 false。
                - isAccurate 表示学者姓名聚合过滤是否精确；明确姓名时通常为 true，没有姓名条件时为 false。
                - strictMode=true 表示只保留严格符合指定机构条件的学者。仅当用户明确要求“仅限该机构、严格机构匹配”等含义时设为 true；未明确要求时为 false。
                - expandMode=true 表示召回命中文献的全部合作者，默认应为 true；只有用户明确要求保守、收窄或仅保留直接命中作者时才为 false。
                - sortBy 未指定时为 relevance；“命中被引量/被引最多”用 cited；“命中文章数/发文量最多”用 paperNumber；“综合 S/H/P 指数”分别用 score/hscore/pscore；“按年份”用 year。
                - pageIndex 默认 1，pageSize 默认 20，scanBatchSize 默认 300，maxScanRounds 默认 20。
                - rewrittenQuery 用简洁中文准确重述，不增加未表达的限制。
                - 信息足够时 needClarification=false、clarificationQuestions=[]；条件过宽、只有姓名或存在关键歧义时，仍生成保守参数，并给出 1 至 3 个中文澄清问题。
                - 不得添加或省略字段，不得把 null 或布尔值写成字符串。

                【示例一：主题领域学者】
                用户请求：找研究数字经济的学者，限定 2019 到 2023 年，按被引排序。
                正确输出：
                {"rewrittenQuery":"检索2019至2023年研究数字经济的领域学者，并按被引排序","conditions":[{"logic":"and","field":"keyword","value":"数字经济","accurate":false}],"sortBy":"cited","pageIndex":1,"pageSize":20,"yearStart":2019,"yearEnd":2023,"isAccurate":false,"strictMode":false,"expandMode":true,"scanBatchSize":300,"maxScanRounds":20,"needClarification":false,"clarificationQuestions":[]}

                【示例二：姓名、机构与严格模式】
                用户请求：查找南京大学研究社会治理的张三，只考虑南京大学身份，不扩展其他合作者。
                正确输出：
                {"rewrittenQuery":"检索南京大学研究社会治理的张三，严格限定南京大学且不扩展其他合作者","conditions":[{"logic":"and","field":"author","value":"张三","accurate":true},{"logic":"and","field":"keyword","value":"社会治理","accurate":false},{"logic":"and","field":"unit","value":"南京大学","accurate":true}],"sortBy":"relevance","pageIndex":1,"pageSize":20,"yearStart":null,"yearEnd":null,"isAccurate":true,"strictMode":true,"expandMode":false,"scanBatchSize":300,"maxScanRounds":20,"needClarification":false,"clarificationQuestions":[]}

                【示例三：二级学科和排除条件】
                用户请求：找中国文学领域的学者，排除研究网络文学的人。
                正确输出：
                {"rewrittenQuery":"检索中国文学二级学科领域的学者，并排除网络文学方向","conditions":[{"logic":"and","field":"subject","value":"中国文学","accurate":true},{"logic":"not","field":"keyword","value":"网络文学","accurate":false}],"sortBy":"relevance","pageIndex":1,"pageSize":20,"yearStart":null,"yearEnd":null,"isAccurate":false,"strictMode":false,"expandMode":true,"scanBatchSize":300,"maxScanRounds":20,"needClarification":false,"clarificationQuestions":[]}

                【示例四：只有姓名，需要澄清】
                用户请求：找学者王伟。
                正确输出：
                {"rewrittenQuery":"查找姓名为王伟的学者","conditions":[{"logic":"and","field":"author","value":"王伟","accurate":true}],"sortBy":"relevance","pageIndex":1,"pageSize":20,"yearStart":null,"yearEnd":null,"isAccurate":true,"strictMode":false,"expandMode":true,"scanBatchSize":300,"maxScanRounds":20,"needClarification":true,"clarificationQuestions":["王伟的研究领域或关键词是什么？","是否需要限定所在机构或年份？"]}

                【示例五：明显不是领域学者检索】
                用户请求：给我推荐一部周末看的电影。
                正确输出：
                {"rewrittenQuery":"当前输入不是领域学者检索请求","conditions":[],"sortBy":"relevance","pageIndex":1,"pageSize":20,"yearStart":null,"yearEnd":null,"isAccurate":false,"strictMode":false,"expandMode":true,"scanBatchSize":300,"maxScanRounds":20,"needClarification":true,"clarificationQuestions":["请提供希望查找学者的研究主题或CSSCI二级学科。","如有需要，也可以补充机构、作者姓名或年份范围。"]}

                最终只能输出一个合法 JSON 对象，不得输出 Markdown 代码块、注释、前后缀说明或任何额外文本。
                """;
    }
}
