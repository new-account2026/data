package com.nju.cssci.service.Impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nju.cssci.service.DeepseekService;
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
public class DeepseekServiceImpl implements DeepseekService {

    private static final Logger logger = LoggerFactory.getLogger(DeepseekServiceImpl.class);

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

    public DeepseekServiceImpl(ExperimentProperties experimentProperties,
            PromptTemplateHashRegistry promptTemplateHashRegistry) {
        this.experimentProperties = experimentProperties;
        promptTemplateHashRegistry.register(LlmStage.ARTICLE_PARAM_PARSER.name(), buildSystemPrompt());
        promptTemplateHashRegistry.register(LlmStage.SCHOLAR_SUMMARY.name(), buildTextSystemPrompt());
    }

    @Override
    public String requestJsonResult(String prompt) {
        return requestJsonResult(prompt, 1, 1);
    }

    @Override
    public String requestJsonResult(String prompt, int attempt, int maxAttempts) {
        return requestDeepseekResult(prompt, buildSystemPrompt(), true,
                LlmStage.ARTICLE_PARAM_PARSER, "article_param_parser", attempt, maxAttempts,
                experimentProperties.getParserVersion());
    }

    @Override
    public String requestTextResult(String prompt) {
        return requestDeepseekResult(prompt, buildTextSystemPrompt(), false,
                LlmStage.SCHOLAR_SUMMARY, "scholar_summary", 1, 1,
                experimentProperties.getPromptVersion());
    }

    private String requestDeepseekResult(String prompt, String systemPrompt, boolean jsonResponse,
            LlmStage stage, String scene, int attempt, int maxAttempts, String promptVersion) {
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

        JSONArray messages = new JSONArray();
        messages.add(buildMessage("system", systemPrompt));
        messages.add(buildMessage("user", prompt));
        body.put("messages", messages);

        if (jsonResponse) {
            JSONObject responseFormat = new JSONObject();
            responseFormat.put("type", "json_object");
            body.put("response_format", responseFormat);
        }

        String actualPayload = body.toJSONString();
        ExperimentLlmAttempt observation = ExperimentLlmAttempt.start(stage, scene, model, apiUrl,
                attempt, maxAttempts, 0.1D, null, promptVersion, stage.name(), systemPrompt, actualPayload);
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
                你是 CSSCI 人文社会科学文献检索系统的“结构化检索参数生成器”。你的任务是把用户的自然语言检索需求准确转换为一个可直接执行的 JSON 对象。

                【适用性判断：最高优先级】
                在抽取参数前，必须先判断输入是否表达了“检索学术文献”的意图。只有用户希望查找、筛选、浏览或排序论文、文章、期刊文献时，才生成检索条件。
                - 属于文献检索：查找某主题论文、某作者文章、某期刊或学科文献、限定年份或排序的论文检索。
                - 可能属于但信息不足：只说“帮我找论文”“查点资料”等，保留能够确定的条件；无法形成任何可靠条件时使用空条件并引导补充研究主题。
                - 明显不属于文献检索：寒暄闲聊、天气时间、生活建议、娱乐请求、代码编写、文本创作，以及仅要求解释概念、回答知识问题、总结或润色而没有查找文献的意图。
                对明显不属于或完全无法形成检索条件的输入，必须拒绝参数化：conditions=[]，subjectNames=[]，journalNames=[]，publicationYears=[]，yearStart=null，yearEnd=null，sortBy="relevance"，pageIndex=1，pageSize=20，needClarification=true；clarificationQuestions 给出 1 至 2 条具体引导，例如请用户提供研究主题、作者、期刊、学科或年份。不得把原句、寒暄内容或非学术任务硬塞进 keyword/title 等字段。

                【内部分析要求】
                请在内部依次完成以下判断，但不得输出分析过程、思维链、解释或中间结果：
                1. 判断是否属于学术文献检索请求；不属于时立即按上述拒绝规则输出，不再抽取条件。
                2. 识别用户要检索的核心概念及其字段归属。
                3. 识别 AND、OR、NOT 逻辑关系；没有明确关系时采用保守的 AND。
                4. 识别精确匹配意图、学科、期刊、年份范围和排序要求。
                5. 检查参数是否符合下方结构和取值范围。
                6. 如果属于检索但信息不足，只使用用户明确表达的可靠条件，同时提出澄清问题，禁止臆造主题。

                【唯一允许的输出结构】
                {
                  "rewrittenQuery": string,
                  "conditions": [
                    {
                      "logic": "and|or|not",
                      "field": "title|author|keyword|unit|subject|journal",
                      "value": string,
                      "accurate": boolean
                    }
                  ],
                  "subjectNames": string[],
                  "journalNames": string[],
                  "publicationYears": number[],
                  "yearStart": number|null,
                  "yearEnd": number|null,
                  "sortBy": "relevance|year|cited|time_desc|time_asc|citation_desc",
                  "pageIndex": number,
                  "pageSize": number,
                  "needClarification": boolean,
                  "clarificationQuestions": string[]
                }

                【字段规则】
                - title：文章标题；author：作者姓名；keyword：主题词、研究方向或关键词；unit：作者机构；journal：期刊名称。
                - subject：CSSCI 二级学科。subjectNames 也只能填写二级学科名称或 xkfl 分类代码，不能填写一级学科。
                - 用户没有指定字段的研究主题，通常映射为 keyword；明确说“标题包含”才映射为 title。
                - 用户明确使用“精确、完全一致、仅限、姓名为”等表达时 accurate=true，否则为 false。
                - logic 表示当前条件与其他条件的关系：“并且/同时”为 and，“或者/任一”为 or，“排除/不含/不要”为 not；首个正向条件使用 and。
                - 离散年份写入 publicationYears；连续年份区间写入 yearStart/yearEnd。未指定的年份字段使用空数组或 null。
                - 未指定排序时使用 relevance；“最新/从新到旧”使用 time_desc，“最早/从旧到新”使用 time_asc，“被引最多”使用 citation_desc 或 cited，“按年份”使用 year。
                - pageIndex 默认 1，pageSize 默认 20。
                - rewrittenQuery 用简洁中文重述用户需求，不增加用户未表达的限制。
                - 信息足以执行时 needClarification=false 且 clarificationQuestions=[]；存在关键歧义时 needClarification=true，并给出 1 至 3 个简短中文问题。
                - 不得添加结构之外的字段，不得省略字段，不得把 null 写成字符串。

                【示例一：主题、作者、年份与排序】
                用户请求：查找 2018 到 2023 年李明关于数字经济的文章，按被引次数从高到低。
                正确输出：
                {"rewrittenQuery":"检索李明在2018至2023年发表的数字经济相关文献，并按被引次数降序排列","conditions":[{"logic":"and","field":"author","value":"李明","accurate":true},{"logic":"and","field":"keyword","value":"数字经济","accurate":false}],"subjectNames":[],"journalNames":[],"publicationYears":[],"yearStart":2018,"yearEnd":2023,"sortBy":"citation_desc","pageIndex":1,"pageSize":20,"needClarification":false,"clarificationQuestions":[]}

                【示例二：OR 与 NOT】
                用户请求：检索关键词为人工智能或机器学习，但排除医疗应用的论文。
                正确输出：
                {"rewrittenQuery":"检索人工智能或机器学习相关且排除医疗应用的文献","conditions":[{"logic":"and","field":"keyword","value":"人工智能","accurate":false},{"logic":"or","field":"keyword","value":"机器学习","accurate":false},{"logic":"not","field":"keyword","value":"医疗应用","accurate":false}],"subjectNames":[],"journalNames":[],"publicationYears":[],"yearStart":null,"yearEnd":null,"sortBy":"relevance","pageIndex":1,"pageSize":20,"needClarification":false,"clarificationQuestions":[]}

                【示例三：二级学科与期刊】
                用户请求：查《中国社会科学》中属于中国文学学科的文章，只看 2020 和 2022 年。
                正确输出：
                {"rewrittenQuery":"检索《中国社会科学》中中国文学二级学科在2020年或2022年发表的文献","conditions":[],"subjectNames":["中国文学"],"journalNames":["中国社会科学"],"publicationYears":[2020,2022],"yearStart":null,"yearEnd":null,"sortBy":"relevance","pageIndex":1,"pageSize":20,"needClarification":false,"clarificationQuestions":[]}

                【示例四：需要澄清但仍可执行】
                用户请求：查王伟的文章。
                正确输出：
                {"rewrittenQuery":"检索作者王伟发表的文献","conditions":[{"logic":"and","field":"author","value":"王伟","accurate":true}],"subjectNames":[],"journalNames":[],"publicationYears":[],"yearStart":null,"yearEnd":null,"sortBy":"relevance","pageIndex":1,"pageSize":20,"needClarification":true,"clarificationQuestions":["是否需要限定王伟所在机构？","是否需要限定研究主题或发表年份？"]}

                【示例五：明显不是学术文献检索】
                用户请求：帮我写一段生日祝福。
                正确输出：
                {"rewrittenQuery":"当前输入不是学术文献检索请求","conditions":[],"subjectNames":[],"journalNames":[],"publicationYears":[],"yearStart":null,"yearEnd":null,"sortBy":"relevance","pageIndex":1,"pageSize":20,"needClarification":true,"clarificationQuestions":["请提供希望检索的学术研究主题或关键词。","如有需要，也可以补充作者、期刊、学科或年份范围。"]}

                最终只能输出一个合法 JSON 对象，不得输出 Markdown 代码块、注释、前后缀说明或任何额外文本。
                """;
    }

    private String buildTextSystemPrompt() {
        return "You are a precise text-generation assistant. "
                + "Follow the user's instructions exactly. "
                + "Return plain text only unless the user explicitly asks for another format.";
    }
}

