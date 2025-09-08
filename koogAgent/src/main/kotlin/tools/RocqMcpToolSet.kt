package org.example.tools

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * This class only contains the wrappers over actual tool-calls;
 * signature of the methods is basically the only thing seen by the agent
 */
@LLMDescription("Tools for interacting with my MCP Coq server")
class RocqMcpToolSet(
    private val proofSessionManager: RocqProofSessionManager
) : ToolSet {
    @Tool
    @LLMDescription("Get project root info from MCP server")
    fun getProjectRoot() = proofSessionManager.callTool("get_project_root", false)

    @Tool
    @LLMDescription("Returns a list of all Coq files in the project")
    fun listCoqFiles() = proofSessionManager.callTool("list_coq_files", false)

    @Tool
    @LLMDescription("Retrieves available theorem names from a file, including the target theorem.")
    fun getTheoremNamesFromFileWithTargetTheorem(
        @LLMDescription("Path to the Coq file")
        filePath: String,
    ) = proofSessionManager.callTool(
        "get_theorem_names_from_file_with_target_theorem",
        true,
        mapOf("filePath" to BodyParam.Str(filePath))
    )

    @Tool
    @LLMDescription("Retrieves available theorem names from a file with target theorem excluded from the list.")
    fun getTheoremNamesFromFileWithoutTargetTheorem(
        @LLMDescription("Path to the Coq file")
        filePath: String,
    ) = proofSessionManager.callTool(
        "get_theorem_names_from_file_without_target_theorem",
        false,
        mapOf("filePath" to BodyParam.Str(filePath))
    )

    @Tool
    @LLMDescription("Returns the stage of the proof for the target theorem in the current session.")
    fun getCurrentTargetTheoremState() = proofSessionManager.callTool(
        "get_current_target_theorem_state",
        true,
        mapOf("proofVersionHash" to BodyParam.Str(proofSessionManager.proofHash))
    )

    @Tool
    @LLMDescription("Given the theorem's name, returns the theorem with its proof.")
    fun getSpecificTheoremWithProofByName(
        @LLMDescription("Path to the Coq file")
        filePath: String,
        @LLMDescription("Name of the theorem to retrieve")
        theoremName: String,
    ) = proofSessionManager.callTool(
        "get_specific_theorem_with_proof_by_name",
        true,
        mapOf(
            "filePath" to BodyParam.Str(filePath),
            "theoremName" to BodyParam.Str(theoremName),
            "proofVersionHash" to BodyParam.Str(proofSessionManager.proofHash),
        )
    )

    /**
     * As this method updates the state of the Rocq proof session manager, the request is done directly through the
     * Rocq project server, bypassing the MCP
     */
    @Tool
    @LLMDescription(
        "Validates a proof (or a part of a proof) in the context of a session and returns either of the following:\n" +
        "(i) That there are no more goals to prove\n" +
        "(ii) Provided proof produces no errors, but the goal is not fully solved. Returns: updated goal state\n" +
        "(iii) The current goal is solved, but there are more goals at other depth levels. Returns: first unsolved goal at the closest depth level\n" +
        "(iv) Provided proof produces errors. Returns: error message"
    )
    fun checkProof(
        @LLMDescription("The proof to validate. It should start with 'Proof.'")
        proof: String,
    ) = proofSessionManager.checkProof(proof)

    @Tool
    @LLMDescription("Retrieves similar proofs for a goal in a file")
    fun getSimilarProofs(
        @LLMDescription(
            "The goal to find similar proofs for. Should be a JSON string matching the interface: " +
            "\"{ hypothesis: string[], conclusion: string }\". IT IS A STRING NOT AN OBJECT"
        )
        goal: String,
        @LLMDescription("Path to the Coq file")
        filePath: String,
        @LLMDescription("Maximum number of premises to return")
        maxNumberOfPremises: Int = 7,
    ) = proofSessionManager.callTool(
        "get_similar_proofs",
        true,
        mapOf(
            "goal" to BodyParam.Str(goal),
            "filePath" to BodyParam.Str(filePath),
            "maxNumberOfPremises" to BodyParam.Num(maxNumberOfPremises)
        )
    )

    @Tool
    @LLMDescription(
        "Returns output of Coq Print All command, issued in the context of the current session. This command prints all defined objects in the current file. " +
        "In particular, that would mean printing all statements of theorems available above the one we are trying to prove at the moment of request."
    )
    fun getObjects() = proofSessionManager.callTool("get_objects", true)

    @Tool
    @LLMDescription("Explains a term in the current session's file. Uses About Coq Command.")
    fun aboutTerm(
        @LLMDescription("The term to explain")
        term: String,
    ) = proofSessionManager.callTool(
        "about_term",
        true,
        mapOf("term" to BodyParam.Str(term))
    )

    @Tool
    @LLMDescription(
        "Searches for a pattern in the current session's file. Uses Search Coq Command. An example of a valid command: " +
        "Search (?a + ?b = ?b + ?a). It could be useful for finding lemmas that could be used in the proof."
    )
    fun searchPattern(
        @LLMDescription("The pattern to search for")
        pattern: String,
    ) = proofSessionManager.callTool(
        "search_pattern",
        true,
        mapOf("pattern" to BodyParam.Str(pattern))
    )

    @Tool
    @LLMDescription("Prints a term in the current session's file. Uses Print Coq Command.")
    fun printTerm(
        @LLMDescription("The term to print")
        term: String,
    ) = proofSessionManager.callTool(
        "print_term",
        true,
        mapOf("term" to BodyParam.Str(term))
    )

    @Tool
    @LLMDescription("Checks a term in the current session's file. Uses Check Coq Command. It outputs only the type of the term. In the case of a theorem, it outputs its statement.")
    fun checkTerm(
        @LLMDescription("The term to check")
        term: String,
    ) = proofSessionManager.callTool(
        "check_term",
        true,
        mapOf("term" to BodyParam.Str(term))
    )
}

fun main() {
    runBlocking {
        val dotenv = dotenv()
        val apiKey = dotenv["OPENAI_API_KEY"]
            ?: error("OPENAI_API_KEY not set")

        val mcpSessionManager = McpSessionManager()
        val proofSessionManager = RocqProofSessionManager(
            "eco_alt3",
            "src/basic/Execution_eco.v",
            mcpSessionManager
        )

        proofSessionManager.use { sessionManager ->
            val mcpTools = RocqMcpToolSet(
                sessionManager
            )

            val agent = AIAgent(
                executor = simpleOpenAIExecutor(apiKey),
                systemPrompt = "You are agent that can communicate to the Coq MCP.",
                llmModel = OpenAIModels.Chat.GPT4o,
                toolRegistry = ToolRegistry {
                    tools(mcpTools)
                }
            )

            val result = agent.run("Show me the working directory using getProjectRoot")
            println("AGENT RESULT: $result")
        }
    }
}
