package org.matrixvpn.ai

import org.json.JSONException
import org.json.JSONObject

/**
 * Treats Garlic output as untrusted data. Only a tiny allowlisted policy schema
 * is returned to the VPN host. The gate never executes tunnel actions.
 */
object GarlicPolicyOutputGate {
    enum class Action { KEEP_CURRENT, CONNECT_PROFILE, PROBE_PROFILE, PROPOSE_FAILOVER }

    data class Advice(
        val action: Action,
        val profileId: String?,
        val confidence: Double,
        val reasonCode: String
    )

    fun validateForTunnelState(
        raw: String,
        allowedCandidateIds: Set<String>,
        tunnelActive: Boolean,
        currentProfileId: String?
    ): Advice {
        val advice = validate(raw, allowedCandidateIds)
        when (advice.action) {
            Action.KEEP_CURRENT -> require(tunnelActive) {
                "KEEP_CURRENT is invalid when MATRIX has no active tunnel."
            }
            Action.CONNECT_PROFILE -> require(!tunnelActive) {
                "CONNECT_PROFILE is valid only when no VPN tunnel is active."
            }
            Action.PROBE_PROFILE -> require(advice.profileId != currentProfileId) {
                "MATRIX cannot probe the profile that is already active."
            }
            Action.PROPOSE_FAILOVER -> {
                require(tunnelActive) {
                    "PROPOSE_FAILOVER is valid only when an active tunnel exists."
                }
                require(advice.profileId != currentProfileId) {
                    "A failover recommendation must select a different profile."
                }
            }
        }
        return advice
    }

    fun validate(raw: String, allowedCandidateIds: Set<String>): Advice {
        require(raw.length in 2..4096) { "Garlic policy response has an invalid length." }

        val json = try {
            JSONObject(raw.trim())
        } catch (error: JSONException) {
            throw IllegalArgumentException("Garlic policy response is not valid JSON.", error)
        }

        val allowedKeys = setOf("action", "profile_id", "confidence", "reason_code")
        val keys = json.keys().asSequence().toSet()
        require(keys == allowedKeys) { "Garlic policy response has unexpected or missing fields." }

        // Do not allow org.json's permissive String/Number coercions for model output.
        val actionText = json.get("action") as? String
            ?: throw IllegalArgumentException("Garlic returned a non-string action.")
        val action = when (actionText) {
            "KEEP_CURRENT" -> Action.KEEP_CURRENT
            "CONNECT_PROFILE" -> Action.CONNECT_PROFILE
            "PROBE_PROFILE" -> Action.PROBE_PROFILE
            "PROPOSE_FAILOVER" -> Action.PROPOSE_FAILOVER
            else -> throw IllegalArgumentException("Garlic returned a forbidden policy action.")
        }

        val confidenceValue = json.get("confidence")
        require(confidenceValue is Number) { "Garlic returned a non-numeric confidence value." }
        val confidence = confidenceValue.toDouble()
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Garlic returned an invalid confidence value."
        }

        val reason = json.get("reason_code") as? String
            ?: throw IllegalArgumentException("Garlic returned a non-string reason code.")
        require(reason.matches(Regex("[A-Z0-9_]{1,48}"))) {
            "Garlic returned an invalid reason code."
        }

        val profileValue = json.get("profile_id")
        val profileId = if (profileValue === JSONObject.NULL) {
            null
        } else {
            profileValue as? String
                ?: throw IllegalArgumentException("Garlic returned a non-string profile ID.")
        }
        if (profileId != null) {
            require(profileId.length in 1..128 && profileId.isNotBlank()) {
                "Garlic returned an invalid profile ID."
            }
        }

        when (action) {
            Action.KEEP_CURRENT -> require(profileId == null) {
                "KEEP_CURRENT cannot name another profile."
            }
            Action.CONNECT_PROFILE, Action.PROBE_PROFILE, Action.PROPOSE_FAILOVER -> {
                require(profileId != null && profileId in allowedCandidateIds) {
                    "Garlic attempted to use a profile that MATRIX did not authorize."
                }
            }
        }
        return Advice(action, profileId, confidence, reason)
    }
}
