package org.example.utils

import ai.jetbrains.code.prompt.executor.clients.grazie.koog.model.GrazieEnvironment
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import kotlin.io.path.exists
import ai.grazie.model.cloud.AuthType
import ai.koog.prompt.llm.LLModel
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

@ConsistentCopyVisibility
data class AgentConfig private constructor(
    val agentId: String,
    val agentVersion: String,

    @param:JsonProperty("coq_project_server_url")
    val coqProjectServerBaseUrl: String,
    @param:JsonProperty("mcp_server_url")
    val mcpServerBaseUrl: String,
    @param:JsonProperty("langfuse_host")
    val langfuseHostUrl: String,

    @param:JsonProperty("path_to_theorems")
    val pathToTheorems: String,

    val backend: Backend,
    @param:JsonProperty("how_many_plans_to_generate")
    val numPlansToGenerate: Int,
    @param:JsonProperty("how_many_best_plans_to_execute")
    val numBestPlansToExec: Int,
    @param:JsonProperty("planning_type")
    val planningType: PlanningType,
    @param:JsonProperty("maximum_premises_from_ranker")
    val maximumPremisesFromRanker: Int,
    @param:JsonProperty("allowed_failed_proof_checks_in_row")
    val allowedFailedProofChecks: Int,
    @param:JsonProperty("total_allowed_tool_calls")
    val totalAllowedToolCalls: Int,

    val defaults: Defaults,
    val grazie: GrazieConfig,

    @param:JsonProperty("planning")
    private val rawPlanning: PlanningConfig = PlanningConfig(),

    @param:JsonProperty("generators")
    private val rawGenerators: GeneratorsConfig = GeneratorsConfig(),

    @JsonIgnore
    val apiTokens: APITokens = APITokens()
) {
    init {
        validatePositive(numPlansToGenerate, "how_many_plans_to_generate")
        validatePositive(numBestPlansToExec, "how_many_best_plans_to_execute")
        validatePositive(maximumPremisesFromRanker, "maximum_premises_from_ranker")
        validatePositive(allowedFailedProofChecks, "allowed_failed_proof_checks_in_row")
        validatePositive(totalAllowedToolCalls, "total_allowed_tool_calls")
    }

    private fun validatePositive(value: Int, fieldName: String) {
        require(value > 0) {
            "$fieldName must be greater than 0, but was $value"
        }
    }

    fun resolved(): ResolvedAgentConfig = ResolvedAgentConfig(
        agentId = agentId,
        agentVersion = agentVersion,
        coqProjectServerBaseUrl = coqProjectServerBaseUrl,
        mcpServerBaseUrl = mcpServerBaseUrl,
        langfuseHostUrl = langfuseHostUrl,
        pathToTheorems = pathToTheorems,
        backend = backend,
        numPlansToGenerate = numPlansToGenerate,
        numBestPlansToExec = numBestPlansToExec,
        planningType = planningType,
        maximumPremisesFromRanker = maximumPremisesFromRanker,
        allowedFailedProofChecks = allowedFailedProofChecks,
        totalAllowedToolCalls = totalAllowedToolCalls,
        defaults = defaults,
        grazie = grazie,
        planning = rawPlanning.resolved(defaults),
        generators = rawGenerators.resolved(defaults),
        apiTokens = apiTokens
    )
}

data class ResolvedAgentConfig(
    val agentId: String,
    val agentVersion: String,
    val coqProjectServerBaseUrl: String,
    val mcpServerBaseUrl: String,
    val langfuseHostUrl: String,
    val pathToTheorems: String,
    val backend: Backend,
    val numPlansToGenerate: Int,
    val numBestPlansToExec: Int,
    val planningType: PlanningType,
    val maximumPremisesFromRanker: Int,
    val allowedFailedProofChecks: Int,
    val totalAllowedToolCalls: Int,
    val defaults: Defaults,
    val grazie: GrazieConfig,
    val planning: ResolvedPlanningConfig,
    val generators: ResolvedGeneratorsConfig,
    val apiTokens: APITokens
)

data class Defaults(
    @param:JsonProperty("max_tokens_to_sample", required = true)
    val maxTokens: Int,
    @param:JsonProperty(required = true)
    val temperature: Double,
    @param:JsonProperty(required = true)
    val profile: String
)

enum class Backend(val value: String) {
    Grazie("grazie"),
    OpenAI("openai"),
    Anthropic("anthropic"),
    OpenRouter("openrouter");

    @JsonValue
    override fun toString(): String = value

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String): Backend =
            when (value.lowercase()) {
                "grazie" -> Grazie
                "openai" -> OpenAI
                "anthropic" -> Anthropic
                "openrouter" -> OpenRouter
                else -> throw IllegalArgumentException("Unknown backend: $value (must be 'grazie' or 'openai')")
            }
    }
}

data class GrazieConfig(
    @param:JsonProperty("client_auth_type", required = true)
    private val rawAuthType: String,
    @param:JsonProperty("grazie_environment", required = true)
    private val rawGrazieEnvironment: String
) {
    @get:JsonIgnore
    val clientAuthType: AuthType
        get() = when (rawAuthType.lowercase()) {
            "user" -> AuthType.User
            "application" -> AuthType.Application
            else -> throw IllegalArgumentException("Unknown auth_type: $rawAuthType")
        }

    @get:JsonIgnore
    val grazieEnvironment: GrazieEnvironment
        get() = when (rawGrazieEnvironment.lowercase()) {
            "staging" -> GrazieEnvironment.Staging
            "production" -> GrazieEnvironment.Production
            else -> throw IllegalArgumentException("Unknown grazie_environment: $rawGrazieEnvironment")
        }

    override fun toString(): String =
        "GrazieConfig(clientAuthType=$clientAuthType, grazieEnvironment=$grazieEnvironment)"
}

internal data class ModelConfig(
    @param:JsonProperty("max_tokens_to_sample")
    private val rawMaxTokens: String? = null,

    @param:JsonProperty("temperature")
    private val rawTemperature: String? = null,

    @param:JsonProperty("profile")
    private val rawProfile: String? = null
) {
    fun resolved(defaults: Defaults): ResolvedModelConfig {
        val profileKey = rawProfile?.takeIf { it.isNotBlank() } ?: defaults.profile
        val profile = LLM_MODELS[profileKey]
            ?: throw IllegalArgumentException("Unknown profile: $profileKey, known models are:\n${listKnownProfiles()}")

        return ResolvedModelConfig(
            maxTokens = rawMaxTokens?.takeIf { it.isNotBlank() }?.toInt() ?: defaults.maxTokens,
            temperature = rawTemperature?.takeIf { it.isNotBlank() }?.toDouble() ?: defaults.temperature,
            profile = profile
        )
    }
}

data class ResolvedModelConfig(
    val maxTokens: Int,
    val temperature: Double,
    val profile: LLModel
)

internal data class PlanningConfig(
    @param:JsonProperty("plan_ranker")
    private val rawPlanRanker: ModelConfig = ModelConfig(),

    @param:JsonProperty("mad_planning")
    private val rawMadPlanning: MadPlanning = MadPlanning(),

    @param:JsonProperty("simple_planning")
    private val rawSimplePlanning: ModelConfig = ModelConfig()
) {
    fun resolved(defaults: Defaults) = ResolvedPlanningConfig(
        planRanker = rawPlanRanker.resolved(defaults),
        madPlanning = rawMadPlanning.resolved(defaults),
        simplePlanning = rawSimplePlanning.resolved(defaults)
    )
}

data class ResolvedPlanningConfig(
    val planRanker: ResolvedModelConfig,
    val madPlanning: ResolvedMadPlanning,
    val simplePlanning: ResolvedModelConfig
)

enum class PlanningType(val value: String) {
    MAD("mad"),
    Simple("simple");

    @JsonValue
    override fun toString(): String = value

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String): PlanningType =
            when (value.lowercase()) {
                "mad" -> MAD
                "simple" -> Simple
                else -> throw IllegalArgumentException("Unknown planning type: $value (must be 'planning' or 'mad')")
            }
    }
}

internal data class MadPlanning(
    @param:JsonProperty("mad_rounds_number")
    private val madRoundsNumber: Int = 2,

    @param:JsonProperty("pro_plan_model")
    private val rawProPlan: ModelConfig = ModelConfig(),

    @param:JsonProperty("con_plan_model")
    private val rawConPlan: ModelConfig = ModelConfig(),

    @param:JsonProperty("judge_model")
    private val rawJudge: ModelConfig = ModelConfig()
) {
    init {
        require(madRoundsNumber > 0) {
            "mad_rounds_number must be greater than 0, but was $madRoundsNumber"
        }
    }

    fun resolved(defaults: Defaults) = ResolvedMadPlanning(
        madRoundsNumber = madRoundsNumber,
        proPlan = rawProPlan.resolved(defaults),
        conPlan = rawConPlan.resolved(defaults),
        judge = rawJudge.resolved(defaults)
    )
}

data class ResolvedMadPlanning(
    val madRoundsNumber: Int,
    val proPlan: ResolvedModelConfig,
    val conPlan: ResolvedModelConfig,
    val judge: ResolvedModelConfig
)

internal data class GeneratorsConfig(
    @param:JsonProperty("executor")
    private val rawExecutor: ModelConfig = ModelConfig(),

    @param:JsonProperty("proof_progress_critic")
    private val rawProofProgressCritic: ModelConfig = ModelConfig(),

    @param:JsonProperty("replanner")
    private val rawReplanner: ModelConfig = ModelConfig(),

    @param:JsonProperty("summarizer")
    private val rawSummarizer: ModelConfig = ModelConfig(),

    @param:JsonProperty("plan_failure_summarizer")
    private val rawPlanFailureSummarizer: ModelConfig = ModelConfig(),

    @param:JsonProperty("similar_theorems_analyzer")
    private val rawSimilarTheoremsAnalyzer: ModelConfig = ModelConfig()
) {
    fun resolved(defaults: Defaults) = ResolvedGeneratorsConfig(
        executor = rawExecutor.resolved(defaults),
        proofProgressCritic = rawProofProgressCritic.resolved(defaults),
        replanner = rawReplanner.resolved(defaults),
        summarizer = rawSummarizer.resolved(defaults),
        planFailureSummarizer = rawPlanFailureSummarizer.resolved(defaults),
        similarTheoremsAnalyzer = rawSimilarTheoremsAnalyzer.resolved(defaults)
    )
}

data class ResolvedGeneratorsConfig(
    val executor: ResolvedModelConfig,
    val proofProgressCritic: ResolvedModelConfig,
    val replanner: ResolvedModelConfig,
    val summarizer: ResolvedModelConfig,
    val planFailureSummarizer: ResolvedModelConfig,
    val similarTheoremsAnalyzer: ResolvedModelConfig
)

data class APITokens(
    val grazieApiToken: String? = getEnv("GRAZIE_TOKEN"),
    val openAiApiToken: String? = getEnv("OPENAI_API_KEY"),
    val anthropicApiToken: String? = getEnv("ANTHROPIC_API_KEY"),
    val openRouterApiToken: String? = getEnv("OPEN_ROUTER_API_KEY"),
    val langfusePublicKey: String? = getEnv("LANGFUSE_PUBLIC_KEY"),
    val langfusePrivateKey: String? = getEnv("LANGFUSE_SECRET_KEY")
)

internal fun loadAgentConfig(filePath: Path): ResolvedAgentConfig {
    if (!filePath.exists()) {
        error("Config file $filePath not found")
    }

    val mapper = YAMLMapper()
        .registerModule(
            KotlinModule.Builder()
                .configure(KotlinFeature.NullIsSameAsDefault, true)
                .build()
        )
        .apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        }

    val rawConfig = mapper.readValue(filePath.toFile(), AgentConfig::class.java)
    return rawConfig.resolved()
}