package org.july.release
import com.cloudbees.groovy.cps.NonCPS
class TemplateText {
    @NonCPS
    static String renderTemplate(String template, Map<String, String> replacements) {
        def rendered = template
        replacements.each { key, value ->
            rendered = rendered.replace("@@${key}@@", value)
        }
        def unresolved = (rendered =~ /@@[A-Z0-9_]+@@/)
        if (unresolved.find()) {
            throw new IllegalStateException("专用模板缺少替换值: ${unresolved.group(0)}")
        }
        return rendered
    }

    @NonCPS
    static String groovyList(List<String> values) {
        return '[' + values.collect { groovyString(it) }.join(', ') + ']'
    }

    @NonCPS
    static String groovyString(String value) {
        def escaped = value
            .replace('\\', '\\\\')
            .replace("'", "\\'")
            .replace('\r', '\\r')
            .replace('\n', '\\n')
        return "'${escaped}'"
    }

    @NonCPS
    static String xmlEscape(String value) {
        return value
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
            .replace("'", '&apos;')
    }
}
