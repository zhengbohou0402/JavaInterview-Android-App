import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.IncludeSourceSpans
import java.util.regex.Pattern

@Grab(group='org.commonmark', module='commonmark', version='0.21.0')

def urls = [
    "docs/java/basis/java-basic-questions-01.md",
    "docs/java/collection/java-collection-questions-01.md",
    "docs/java/concurrent/java-concurrent-questions-01.md",
    "docs/database/mysql/mysql-questions-01.md"
]

Pattern EXCLUDE_TITLES = Pattern.compile("^(基础|集合|前言|总结|相关阅读|扩展阅读|核心概念|重要知识点|源码分析|JVM|JDK|背景|示例|注意事项|图片|参考资料|作者说明)$")
Pattern INCLUDE_WORDS = Pattern.compile("(什么|为什么|如何|怎么|哪些|区别|联系|优缺点|原理|过程|作用|场景|是否|能否|介绍一下|vs)", Pattern.CASE_INSENSITIVE)

for (String path : urls) {
    String url = "https://raw.githubusercontent.com/Snailclimb/JavaGuide/main/" + path
    String markdownContent = new URL(url).text
    
    Parser parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    Node document = parser.parse(markdownContent)
    
    String[] lines = markdownContent.split("\n")
    List<Heading> allHeadings = []
    
    document.accept(new AbstractVisitor() {
        @Override
        void visit(Heading heading) {
            allHeadings.add(heading)
            super.visit(heading)
        }
    })
    
    int extractedCount = 0
    List<String> excluded = []
    List<Map> questions = []
    
    for (int i = 0; i < allHeadings.size(); i++) {
        Heading heading = allHeadings.get(i)
        StringBuilder sb = new StringBuilder()
        heading.accept(new AbstractVisitor() {
            @Override
            void visit(Text text) { sb.append(text.literal) }
            @Override
            void visit(Code code) { sb.append(code.literal) }
        })
        String headingText = sb.toString().trim()
        
        String cleaned = headingText.replaceAll("^[0-9\\.\\-\\s、①②③④⑤⑥⑦⑧⑨⑩一二三四五六七八九十]+", "").trim()
        
        boolean isValid = false
        String reason = ""
        
        if (cleaned.length() < 4) {
            reason = "Length < 4"
        } else if (EXCLUDE_TITLES.matcher(cleaned).find()) {
            reason = "Matches EXCLUDE_TITLES"
        } else if (cleaned.endsWith("?") || cleaned.endsWith("？")) {
            isValid = true
        } else if (INCLUDE_WORDS.matcher(cleaned).find()) {
            isValid = true
        } else {
            reason = "No question words"
        }
        
        if (!isValid) {
            excluded.add(headingText + " (" + reason + ")")
            continue
        }
        
        int startLine = heading.sourceSpans ? heading.sourceSpans[-1].lineIndex + 1 : 0
        int endLine = lines.length
        
        for (int j = i + 1; j < allHeadings.size(); j++) {
            if (allHeadings.get(j).level <= heading.level) {
                endLine = allHeadings.get(j).sourceSpans ? allHeadings.get(j).sourceSpans[0].lineIndex : lines.length
                break
            }
        }
        
        if (startLine < endLine) {
            StringBuilder mdBuilder = new StringBuilder()
            for (int l = startLine; l < endLine; l++) mdBuilder.append(lines[l]).append("\n")
            String rawMd = mdBuilder.toString().trim()
            String plainText = rawMd.replaceAll("<[^>]*>", "").replaceAll("(?m)^[#\\-\\+\\*>]+\\s+", "").replaceAll("`", "").trim()
            int chineseCount = plainText.findAll(/[\\u4e00-\\u9fa5]/).size()
            
            if (chineseCount >= 50) {
                extractedCount++
                questions.add([q: headingText, a: plainText.take(50).replaceAll("\n", " ") + "..."])
            } else {
                excluded.add(headingText + " (Chinese characters < 50)")
            }
        }
    }
    
    println "========================================="
    println "File: " + path
    println "Extracted Questions Count: " + extractedCount
    println "Excluded Samples: " + excluded.take(3)
    println "Random 5 Samples:"
    Collections.shuffle(questions)
    for (int i = 0; i < Math.min(5, questions.size()); i++) {
        println " Q${i+1}: ${questions[i].q}"
        println "    A: ${questions[i].a}"
    }
    println "=========================================\n"
}
