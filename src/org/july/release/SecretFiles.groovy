package org.july.release

import com.cloudbees.groovy.cps.NonCPS
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor

/** 文件内容只在非 CPS 校验中使用，不返回或记录真实凭证。 */
class SecretFiles {
    @NonCPS
    static void validate(File file, String fieldName) {
        if (file.length() > 1024 * 1024)
            throw new IllegalStateException('凭证文件超过 1 MiB: ' + fieldName)
        String text
        try {
            text = file.getText('UTF-8').replace('\uFEFF', '').trim()
        } catch (Exception ignored) {
            throw new IllegalStateException('凭证文件无法读取: ' + fieldName)
        }
        if (!text || text.contains('__REQUIRED__'))
            throw new IllegalStateException('凭证文件未填写或仍有 __REQUIRED__ 占位符: ' + fieldName + ' (' + file.name + ')')
        if (fieldName == 'cos.configPath') validateCos(text)
    }

    @NonCPS
    private static void validateCos(String text) {
        def data
        try {
            def options = new LoaderOptions()
            options.setAllowDuplicateKeys(false)
            options.setCodePointLimit(1024 * 1024)
            data = new Yaml(new SafeConstructor(options)).load(text)
        } catch (Exception ignored) {
            // YAML 异常可能包含原始密钥行；不要传递异常消息或 cause。
            throw new IllegalStateException('cos.configPath: YAML 格式错误，请检查 cos.yaml；内容已隐藏')
        }
        if (!(data instanceof Map) || !(data.cos instanceof Map) || !(data.cos.base instanceof Map))
            throw new IllegalStateException('cos.configPath: 缺少 cos.base 配置')
        ['secretid', 'secretkey'].each { key ->
            if (!filled(data.cos.base[key]))
                throw new IllegalStateException('cos.configPath: cos.base.' + key + ' 未填写')
        }
        if (!(data.cos.base.protocol in ['https', 'http']))
            throw new IllegalStateException('cos.configPath: cos.base.protocol 必须为 https 或 http')
        def buckets = data.cos.buckets
        if (!(buckets instanceof List) || buckets.isEmpty())
            throw new IllegalStateException('cos.configPath: 至少配置一个 cos.buckets 存储桶')
        buckets.eachWithIndex { bucket, index ->
            if (!(bucket instanceof Map) || !filled(bucket.name))
                throw new IllegalStateException('cos.configPath: buckets[' + index + '].name 未填写')
            if (!filled(bucket.endpoint) && !filled(bucket.region))
                throw new IllegalStateException('cos.configPath: buckets[' + index + '] 必须填写 endpoint 或 region')
        }
    }

    @NonCPS
    private static boolean filled(Object value) {
        value instanceof String && !value.trim().isEmpty()
    }
}
