package com.kkgosu.learnharness.model

import android.content.res.Resources
import com.kkgosu.learnharness.R

object SampleGraph {
    fun create(resources: Resources): GraphData {
        val labels = resources.getStringArray(R.array.graph_node_labels)
        val statements = resources.getStringArray(R.array.graph_node_statements)
        val reasons = resources.getStringArray(R.array.graph_node_reasons)
        val ids = listOf(
            "function", "square", "minimum", "slope", "local",
            "derivative", "power-rule", "positive", "negative", "stationary",
            "descent", "step-size", "update", "overshoot", "convergence",
            "event", "probability", "complement", "independence", "expectation",
            "sample", "mean", "variance", "averaging", "uncertainty",
            "loss", "parameters", "gradient", "batch", "generalization"
        )
        require(labels.size == ids.size && statements.size == ids.size && reasons.size == ids.size)
        val nodes = ids.mapIndexed { index, id ->
            GraphNode(
                id,
                labels[index],
                statements[index],
                reasons[index],
                index / 5
            )
        }
        val edges = listOf(
            "function" to "square",
            "square" to "minimum",
            "function" to "slope",
            "slope" to "local",
            "slope" to "derivative",
            "derivative" to "power-rule",
            "derivative" to "positive",
            "derivative" to "negative",
            "minimum" to "stationary",
            "positive" to "descent",
            "negative" to "descent",
            "descent" to "update",
            "step-size" to "update",
            "step-size" to "overshoot",
            "update" to "convergence",
            "event" to "probability",
            "probability" to "complement",
            "probability" to "independence",
            "probability" to "expectation",
            "expectation" to "mean",
            "sample" to "mean",
            "mean" to "variance",
            "independence" to "averaging",
            "variance" to "uncertainty",
            "averaging" to "uncertainty",
            "loss" to "parameters",
            "parameters" to "gradient",
            "gradient" to "update",
            "sample" to "batch",
            "batch" to "gradient",
            "uncertainty" to "generalization",
            "loss" to "generalization"
        )
        return GraphData(nodes, edges, resources.getStringArray(R.array.graph_clusters).toList())
    }

    val lessonNodes = setOf(
        "square",
        "slope",
        "derivative",
        "power-rule",
        "positive",
        "negative",
        "descent",
        "step-size",
        "update",
        "overshoot"
    )
}
