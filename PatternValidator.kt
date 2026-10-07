package com.example.security

/**
 * Standard Android 3x3 Lockscreen Pattern Validator.
 *
 * Grid indexing:
 * 0 1 2
 * 3 4 5
 * 6 7 8
 *
 * Rules:
 * 1. Minimum 4 dots, maximum 9 dots.
 * 2. All dots must be unique (no duplicate visits).
 * 3. Intermediate dots cannot be skipped unless they have already been visited earlier in the pattern.
 * 4. Never logs or outputs the pattern itself.
 */
object PatternValidator {

    const val MIN_DOTS = 4
    const val MAX_DOTS = 9

    sealed class Result {
        object Valid : Result()
        data class Invalid(val reason: String) : Result()
    }

    /**
     * Maps pairs of collinear dots to their required intermediate dot on a 3x3 grid.
     */
    private val intermediateMap = mapOf(
        (0 to 2) to 1, (2 to 0) to 1,
        (3 to 5) to 4, (5 to 3) to 4,
        (6 to 8) to 7, (8 to 6) to 7,
        (0 to 6) to 3, (6 to 0) to 3,
        (1 to 7) to 4, (7 to 1) to 4,
        (2 to 8) to 5, (8 to 2) to 5,
        (0 to 8) to 4, (8 to 0) to 4,
        (2 to 6) to 4, (6 to 2) to 4
    )

    /**
     * Returns the intermediate dot index between [from] and [to] if one exists on the 3x3 grid.
     */
    fun getIntermediateDot(from: Int, to: Int): Int? {
        return intermediateMap[from to to]
    }

    /**
     * Validates a drawn pattern sequence against standard Android rules.
     */
    fun validate(pattern: List<Int>): Result {
        if (pattern.size < MIN_DOTS) {
            return Result.Invalid("Pattern must connect at least $MIN_DOTS dots.")
        }
        if (pattern.size > MAX_DOTS) {
            return Result.Invalid("Pattern cannot connect more than $MAX_DOTS dots.")
        }

        val visited = mutableSetOf<Int>()
        var previousDot: Int? = null

        for (dot in pattern) {
            if (dot !in 0..8) {
                return Result.Invalid("Invalid dot index on 3x3 grid.")
            }
            if (!visited.add(dot)) {
                return Result.Invalid("Duplicate dot detected; each dot can only be visited once.")
            }

            if (previousDot != null) {
                val intermediate = getIntermediateDot(previousDot, dot)
                // If there is an intermediate dot that was NOT already visited before this segment, the skip is illegal
                if (intermediate != null && !visited.contains(intermediate)) {
                    return Result.Invalid("Cannot skip unvisited intermediate dot.")
                }
            }

            previousDot = dot
        }

        return Result.Valid
    }

    /**
     * Helper to test if a candidate next dot can be legally appended to an existing partial pattern.
     * If the transition skips an intermediate dot that hasn't been visited yet, returns the intermediate dot
     * so the UI can either auto-include it (Android behavior) or flag it.
     */
    fun checkNextDot(currentPattern: List<Int>, nextDot: Int): IntermediateCheck {
        if (nextDot !in 0..8) return IntermediateCheck.Rejected("Invalid dot")
        if (currentPattern.contains(nextDot)) return IntermediateCheck.Rejected("Already visited")
        if (currentPattern.isEmpty()) return IntermediateCheck.Direct

        val lastDot = currentPattern.last()
        val intermediate = getIntermediateDot(lastDot, nextDot)
        return if (intermediate != null && !currentPattern.contains(intermediate)) {
            IntermediateCheck.RequiresIntermediate(intermediate)
        } else {
            IntermediateCheck.Direct
        }
    }

    sealed class IntermediateCheck {
        object Direct : IntermediateCheck()
        data class RequiresIntermediate(val intermediateDot: Int) : IntermediateCheck()
        data class Rejected(val reason: String) : IntermediateCheck()
    }
}
