package org.matrixvpn.ai

import org.json.JSONObject

/**
 * Treats Garlic output as untrusted data. Only a tiny allowlisted policy schema
 * is returned to the VPN host. The gate never executes tunnel actions.
 */
object GarlicPolicyOutputGate {
    enum class Action { KEEP_CURRENT, PROBE_PROFILE, PROPOSE_FAILOVER }

    data class Advice(
        val action: Action,
        val profileId: String?,
        val confidence: Double,
        val reasonCode: String
    )

    fun validate(raw: String, allowedCandidateIds: Set<String>): Advice {
        require(raw.length in 2..4096) { "Garlic policy response has an invalid length." }
        val json = JSONObject(raw.trim())
        val allowedKeys = setOf("action", "profile_id", "confidence", "reason_code")
        val keys = json.keys().asSequence().toSet()
        require(keys == allowedKeys) { "Garlic policy response has unexpected or missing fields." }

        val action = when (json.getString("action")) {
            "KEEP_CURRENT" -> Action.KEEP_CURRENT
            "PROBE_PROFILE" -> Action.PROBE_PROFILE
            "PROPOSE_FAILOVER" -> Action.PROPOSE_FAILOVER
            else -> throw IllegalArgumentException("Garlic returned a forbidden policy action.")
        }
        val confidence = json.getDouble("confidence")
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Garlic returned an invalid confidence value."
        }

        val reason = json.getString("reason_code")
        require(reason.matches(Regex("[A-Z0-9_]{1,48}"))) {
            "Garlic returned an invalid reason code."
        }

        val profileId = if (json.isNull("profile_id")) null else json.getString("profile_id")
        when (action) {
            Action.KEEP_CURRENT -> require(profileId == null) {
                "KEEP_CURRENT cannot name another profile."
            }
            Action.PROBE_PROFILE, Action.PROPOSE_FAILOVER -> {
                require(profileId != null && profileId in allowedCandidateIds) {
                    "Garlic attempted to use a profile that MATRIX did not authorize."
                }
            }
        }
        return Advice(action, profileId, confidence, reason)
    }
}
