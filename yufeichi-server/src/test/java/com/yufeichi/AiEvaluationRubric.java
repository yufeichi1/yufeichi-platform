package com.yufeichi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** Frozen before the paid rerun. Strict rules are auditable but do not replace human semantic review. */
final class AiEvaluationRubric {
    private static final Map<String,List<String>> REQUIRED=Map.ofEntries(
        Map.entry("A01",List.of("(?<![0-9])500(?![0-9])\\s*(?:个)?(?:字符|字)")),
        Map.entry("A02",List.of("已发布")), Map.entry("A03",List.of("(?<![A-Za-z0-9])SHA-?256(?![A-Za-z0-9])")),
        Map.entry("A04",List.of("(?<![0-9])100(?![0-9])\\s*次")),
        Map.entry("A05",List.of("(?<![0-9])(?:7|七)(?![0-9])\\s*份")),
        Map.entry("A06",List.of("(?<![0-9])60(?![0-9])\\s*秒")),
        Map.entry("A07",List.of("(?<![0-9])(?:4|四)(?![0-9])\\s*次")),
        Map.entry("A08",List.of("(?<![A-Za-z0-9-])BAMBOO-826(?![A-Za-z0-9-])")),
        Map.entry("A09",List.of("(?<![A-Za-z0-9-])GALAXY-631(?![A-Za-z0-9-])")),
        Map.entry("A10",List.of("(?<![A-Za-z0-9-])AMBER-942(?![A-Za-z0-9-])")),
        Map.entry("A11",List.of("(?:不会|不|不能)自动发布", "(?:手动|自行|作者).*?(?:保存|发布)")),
        Map.entry("A12",List.of("(?:无答案|没有.*?依据|暂无.*?依据|不.*?(?:编造|捏造|回答))")),
        Map.entry("A13",List.of("(?:不会|不|不能).*?(?:回滚|撤销|撤回|取消).*?(?:保存|文章)")),
        Map.entry("A14",List.of("(?:失败.*?(?:也|仍|会|计入|计数)|(?:计入|计数).*?失败)")),
        Map.entry("A15",List.of("(?<![0-9])0?2[:：]20(?![0-9])", "UTC")),
        Map.entry("A16",List.of("(?<![0-9])(?:2|两|二)(?![0-9])\\s*(?:个|路)?(?:并发|请求|AI)")),
        Map.entry("A17",List.of("(?<![0-9])(?:3|三)(?![0-9])\\s*轮")),
        Map.entry("A18",List.of("Java\\s*21(?![0-9])")));
    private static final Map<String,String> CONTRADICTIONS=Map.ofEntries(
        Map.entry("A01","(?:不是|并非|不为|超过)\\s*500|(?<![0-9])(?!500(?![0-9]))[0-9]+\\s*(?:个)?字符"),
        Map.entry("A02","(?:未发布|草稿|私密).*?(?:也能|可以|允许|均可|都可).*?(?:使用|检索|查询)|(?:使用|检索|包含).*?(?:所有草稿|未发布文章)"),
        Map.entry("A04","(?<![0-9])(?!100(?![0-9]))[0-9]+\\s*次|不是\\s*100"),
        Map.entry("A05","(?<![0-9])(?!7(?![0-9]))[0-9]+\\s*份|不是\\s*7"),
        Map.entry("A06","(?<![0-9])(?!60(?![0-9]))[0-9]+\\s*秒|不是\\s*60"),
        Map.entry("A07","(?<![0-9])(?!4(?![0-9]))[0-9]+\\s*次"),
        Map.entry("A11","(?<!不)(?<!不会)(?<!不能)(?:会|可以)自动发布|无需.*?手动"),
        Map.entry("A12","(?:直接|自动|可以).*?(?:编造|捏造)|(?:外部知识|互联网).*?(?:补全|回答)"),
        Map.entry("A13","(?<!不)(?:会|需要|必须)\\s*(?:回滚|撤销|撤回).*?(?:文章|保存)"),
        Map.entry("A14","失败.*?(?:不计|不会计|不纳入|不统计)|(?:不计|不统计).*?失败"),
        Map.entry("A16","(?<![0-9])(?!2(?![0-9]))[0-9]+\\s*个?(?:并发|请求|AI)"),
        Map.entry("A17","(?<![0-9])(?!3(?![0-9]))[0-9]+\\s*轮"),
        Map.entry("A18","Java\\s*(?!21(?![0-9]))[0-9]+"));
    static boolean facts(String id,String answer) {
        var required=REQUIRED.get(id);if(required==null) return false;
        return required.stream().allMatch(r->matches(r,answer)) && !matches(CONTRADICTIONS.getOrDefault(id,"(?!)"),answer);
    }
    static boolean sources(JsonNode expected,List<String> actual) {
        var allowed=new HashSet<String>();expected.forEach(s->allowed.add(s.asText()));
        return !actual.isEmpty() && allowed.containsAll(actual);
    }
    private static boolean matches(String regex,String text) {return Pattern.compile(regex,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(text).find();}
}
