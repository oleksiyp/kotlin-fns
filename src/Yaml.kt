import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.*
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.InputStream

fun main() {
    println(yaml {
        "key1".."value"
        kv("key2") {
            "key3"..5
            kv("key4") {
                el(5)
                el(6)
                el(7)
            }
            kv("key7") {
                el {
                    el(1)
                    el(2)
                    el(3)
                }
                el {
                    el(4)
                    el(5)
                    el(6)
                }
                el {
                    el(7)
                    el(8)
                    el(9)
                }
            }
        }
    } + yaml {
        el("a")
        el("b")
        el("c")
    })
}

@DslMarker
annotation class YamlDsl

fun yaml(block: YamlNode.() -> Unit) =
    Yaml(listOf(YamlNode().apply(block)))

data class Yaml(val nodes: List<YamlNode>) {
    override fun toString(): String {
        return nodes.joinToString("")
    }

    operator fun plus(bulb: Yaml): Yaml {
        return Yaml(nodes + bulb.nodes)
    }

    companion object {
        fun read(input: InputStream) = input.use {
            Yaml(
                ObjectMapper()
                    .registerKotlinModule()
                    .readValues<ContainerNode<*>>(
                        YAMLFactory().createParser(it),
                        jacksonTypeRef<ContainerNode<*>>()
                    )
                    .readAll()
                    .map { it.toBulb() }
            )
        }

        private fun ContainerNode<*>.toBulb() = toYamlOrValue() as YamlNode

        private fun JsonNode.toYamlOrValue(): Any? = when (this) {
            is ArrayNode -> YamlNode().apply {
                forEach { el(it.toYamlOrValue()) }
            }
            is ObjectNode -> YamlNode().apply {
                fieldNames().forEach { k -> kv(k, get(k)?.toYamlOrValue()) }
            }
            is NumericNode -> numberValue()
            is TextNode -> textValue()
            is NullNode -> null
            is BooleanNode -> booleanValue()
            else -> throw java.lang.RuntimeException("bad node type $this")
        }

    }
}

@YamlDsl
class YamlNode {
    private val map = mutableMapOf<String, Any?>()
    private val list = mutableListOf<Any?>()

    fun toMapOrList(): Any? =
        when {
            map.isNotEmpty() && list.isNotEmpty() ->
                throw RuntimeException("node has both map and list elements")
            map.isNotEmpty() ->
                map.mapValues { (_, value) -> value.toMapOrListOrAny() }
            list.isNotEmpty() ->
                list.map { it.toMapOrListOrAny() }
            else -> null
        }

    private fun Any?.toMapOrListOrAny(): Any? {
        return if (this is YamlNode) {
            toMapOrList()
        } else {
            this
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun toMap() = toMapOrList() as? Map<String, Any?> ?: throw java.lang.RuntimeException("not a map")

    fun toList() = toMapOrList() as? List<Any?> ?: throw java.lang.RuntimeException("not a list")

    override fun toString() = yamlMapper.writeValueAsString(toMapOrList())

    fun kv(key: String, value: Any?) {
        map[key] = value
    }

    operator fun String.rangeTo(other: Any?) {
        kv(this, other)
    }

    fun kv(key: String, fn: YamlNode.() -> Unit) {
        map[key] = YamlNode().apply(fn)
    }

    fun el(value: Any?) {
        list.add(value)
    }

    fun el(fn: YamlNode.() -> Unit) {
        list.add(YamlNode().apply(fn))
    }

    companion object {
        val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    }
}
