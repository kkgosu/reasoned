package com.kkgosu.learnharness.model

import com.kkgosu.learnharness.api.ProviderFailure
import com.kkgosu.learnharness.utils.exactKeys
import com.kkgosu.learnharness.utils.objects
import com.kkgosu.learnharness.utils.strictString
import com.kkgosu.learnharness.utils.strings
import org.json.JSONObject

internal data class Finding(
    val id: String,
    val name: String,
    val statement: String,
    val aliases: List<String>,
    val message: Long
)

internal data class FindingChain(
    val premises: List<String>,
    val conclusion: String,
    val message: Long
)

internal data class Findings(val propositions: List<Finding>, val chains: List<FindingChain>) {
    companion object {
        fun parse(args: JSONObject, batch: List<Passage>, existing: Set<String>): Findings {
            args.exactKeys(setOf("propositions", "chains"))
            val anchors = batch.map { it.id }.toSet()
            fun anchor(value: JSONObject): Long =
                value.strictString("messageId", 30).toLongOrNull()?.takeIf { it in anchors }
                    ?: throw ProviderFailure("invalid_evidence_anchor")

            val propositions = args.getJSONArray("propositions").objects().map { p ->
                p.exactKeys(setOf("id", "name", "statement", "aliases", "messageId"))
                val aliases = p.getJSONArray("aliases").strings(12, 2000)
                Finding(
                    p.strictString("id", 80),
                    p.strictString("name", 120),
                    p.strictString("statement", 2000),
                    aliases,
                    anchor(p)
                )
            }
            if (propositions.size > 32 || propositions.map { it.id }
                    .distinct().size != propositions.size ||
                propositions.any { !it.id.matches(Regex("p[0-9]{1,4}")) || it.id in existing }
            ) throw ProviderFailure("invalid_findings")
            val ids = existing + propositions.map { it.id }
            val chains = args.getJSONArray("chains").objects().map { c ->
                c.exactKeys(setOf("premises", "conclusion", "messageId"))
                val premises = c.getJSONArray("premises").strings(12, 80)
                val conclusion = c.strictString("conclusion", 80)
                if (premises.isEmpty() || premises.distinct().size != premises.size || conclusion in premises ||
                    (premises + conclusion).any { it !in ids }
                ) throw ProviderFailure("invalid_graph_reference")
                FindingChain(premises, conclusion, anchor(c))
            }
            if (chains.size > 48) throw ProviderFailure("knowledge_limit")
            validateDag(ids, chains.flatMap { c -> c.premises.map { it to c.conclusion } })
            return Findings(propositions, chains)
        }
    }
}
