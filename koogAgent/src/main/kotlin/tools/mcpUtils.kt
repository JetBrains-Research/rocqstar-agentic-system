package org.example.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.example.agent.SimilarTheorems
import org.example.agent.Theorem
import java.util.logging.Logger
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation

fun getToolSummary(toolset: ToolSet): String {
    return toolset::class.declaredFunctions
        .filter { it.findAnnotation<Tool>() != null }
        .joinToString("\n") { fn ->
            val desc = fn.findAnnotation<LLMDescription>()?.description ?: "No description"
            "- **${fn.name}**: $desc"
        }
}

/**
 * This method does context retrieval in the file, by exploring other defined theorems
 * and ranking them according to the chosen ranker. By default, it uses RocqStarRanker.
 * This behavior is defined in /src/agentServer/controllers/coqProjectController.ts file
 * of the MCP/Rocq-server project.
 */
fun retrieveContextPremises(
    filePath: String,
    sessionManager: RocqProofSessionManager,
    maximumPremisesFromRanker: Int,
    logger: Logger,
): SimilarTheorems {
    val currentGoals = sessionManager.currentGoals
    // The state in Rocq is described as a list of goals, we iterate over goals,
    // for each of them we fetch theorems with similar goals, and return the concatenated list

    require(currentGoals != null ) { "Coq Project server returned goals = null" }
    if (currentGoals.isEmpty()) {
        logger.warning("Observed state with no goals")
    }

    val premiseNames = mutableListOf<String>()
    for (goal in currentGoals) {
        val premises = sessionManager.getPremises(
            goal,
            filePath,
            maximumPremisesFromRanker
        )
        premiseNames.addAll(premises.premises)
    }

    logger.info("Retrieved ${premiseNames.size} premises for $filePath")

    return premiseNames.map { theoremName ->
        val theorem = sessionManager.getTheorem(filePath, theoremName)
        Theorem(theorem.theoremStatement, theorem.theoremProof)
    }
}

/**
 * @return Human/LLM-readable explanation of what was returned from the server
 * as an answer to the check-proof request. There are non-trivial answers and invariants
 * therefore this helper produces better wrapper-explanation
 */
fun explainCheckProofResponse(response: ProofCheckResponse): ProofCheckResponseExplanation {
    var explanation = response.toString()
    var isProofComplete = false

    if (!response.success) {
        explanation = if (response.error != null) {
            "Unfortunately, the last proof you checked is not valid:\n" +
                    "${response.attemptedProof}\n" +
                    "It fails with the error: ${response.message}\n" +
                    "But it has a valid prefix ${response.validPrefix}\n" +
                    "The goals after this prefix are ${response.goals}\n" +
                    "Please continue to prove the theorem taking the valid prefix into account"
        } else {
            "I couldn't check the last proof you sent. I got ${response.error} " +
                    "Please try again to check proof but avoid using admits and non-obligatory goal focusing."
        }
    }

    // Response is successful
    if (response.message == "Proof is incomplete but valid so far") {
        explanation = "The proof you just checked has no errors but is incomplete:\n" +
                "${response.proof}\n" +
                "The current goals are ${response.goals}\n" +
                "Please continue to prove the theorem taking the valid prefix into account"
    }

    if (response.message
            .startsWith("Your proof is incomplete but valid so far. It has the following goal at the depth")) {
        explanation = "The proof you just checked has no errors but is incomplete:\n" +
                "${response.proof}\n" +
                "You have successfully proved the current branch but there are goals in another branch. ${response.goals}\n" +
                "Please continue to prove the theorem taking the valid prefix into account"
    }

    if (response.message == "Proof complete and valid") {
        explanation = "Proof is complete"
        isProofComplete = true
    }

    return ProofCheckResponseExplanation(
        explanation,
        isProofComplete
    )
}

data class ProofCheckResponseExplanation(
    val explanationMessage: String,
    val isProofComplete: Boolean,
)