package kr.ac.kopo.talkti.backend.experience

/**
 * 경험 DB 조회/저장 로직
 */
object ExperienceRepository {

    // ── 시나리오 분류 ──────────────────────────────────────────

    /**
     * 사용자 명령어에서 시나리오 타입을 추론한다.
     * 키워드 매칭 방식으로 가장 많이 겹치는 시나리오를 반환.
     */
    fun classifyScenario(userCommand: String): String {
        val conn = ExperienceDatabase.getConnection()
        val clean = userCommand.replace(" ", "").lowercase()

        conn.prepareStatement("SELECT scenario_type, keywords FROM scenarios").use { stmt ->
            val rs = stmt.executeQuery()
            var bestType = "기타"
            var bestScore = 0

            while (rs.next()) {
                val type = rs.getString("scenario_type")
                val keywords = rs.getString("keywords").split(",")
                val score = keywords.count { clean.contains(it.replace(" ", "").lowercase()) }
                if (score > bestScore) {
                    bestScore = score
                    bestType = type
                }
            }
            return bestType
        }
    }

    // ── 세션 저장 ──────────────────────────────────────────────

    /**
     * 새 세션을 생성하고 세션 ID를 반환한다.
     */
    fun createSession(scenarioType: String, userCommand: String): Long {
        val conn = ExperienceDatabase.getConnection()
        conn.prepareStatement(
            "INSERT INTO sessions (scenario_type, user_command, success, total_steps) VALUES (?, ?, 0, 0)"
        ).use { stmt ->
            stmt.setString(1, scenarioType)
            stmt.setString(2, userCommand)
            stmt.executeUpdate()
        }
        // 마지막 삽입 ID 조회
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT last_insert_rowid()")
            return if (rs.next()) rs.getLong(1) else -1L
        }
    }

    /**
     * 세션의 성공 여부와 총 단계 수를 업데이트한다.
     */
    fun updateSessionResult(sessionId: Long, success: Boolean, totalSteps: Int) {
        val conn = ExperienceDatabase.getConnection()
        conn.prepareStatement(
            "UPDATE sessions SET success = ?, total_steps = ? WHERE id = ?"
        ).use { stmt ->
            stmt.setInt(1, if (success) 1 else 0)
            stmt.setInt(2, totalSteps)
            stmt.setLong(3, sessionId)
            stmt.executeUpdate()
        }
    }

    // ── 상태 전이 저장 ─────────────────────────────────────────

    /**
     * 상태 전이 한 단계를 저장한다.
     */
    fun saveTransition(
        sessionId: Long,
        step: Int,
        fromState: String,
        toState: String,
        actionTaken: String?
    ) {
        val conn = ExperienceDatabase.getConnection()
        conn.prepareStatement(
            "INSERT INTO state_transitions (session_id, step, from_state, to_state, action_taken) VALUES (?, ?, ?, ?, ?)"
        ).use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.setInt(2, step)
            stmt.setString(3, fromState)
            stmt.setString(4, toState)
            stmt.setString(5, actionTaken ?: "")
            stmt.executeUpdate()
        }
    }

    // ── 성공 경험 조회 ─────────────────────────────────────────

    /**
     * 특정 시나리오의 성공 경험 흐름을 최대 3개 조회한다.
     * 최근 성공 경험 우선 반환.
     */
    fun getSuccessExperiences(scenarioType: String, limit: Int = 3): List<ExperienceRecord> {
        val conn = ExperienceDatabase.getConnection()
        val records = mutableListOf<ExperienceRecord>()

        conn.prepareStatement("""
            SELECT s.id, s.user_command, s.total_steps, s.created_at
            FROM sessions s
            WHERE s.scenario_type = ? AND s.success = 1
            ORDER BY s.created_at DESC
            LIMIT ?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, scenarioType)
            stmt.setInt(2, limit)
            val rs = stmt.executeQuery()

            while (rs.next()) {
                val sessionId = rs.getLong("id")
                val userCommand = rs.getString("user_command")
                val totalSteps = rs.getInt("total_steps")
                val createdAt = rs.getString("created_at")

                // 해당 세션의 상태 전이 흐름 조회
                val transitions = getTransitions(sessionId)
                records.add(ExperienceRecord(sessionId, userCommand, totalSteps, createdAt, transitions))
            }
        }
        return records
    }

    private fun getTransitions(sessionId: Long): List<TransitionRecord> {
        val conn = ExperienceDatabase.getConnection()
        val transitions = mutableListOf<TransitionRecord>()

        conn.prepareStatement("""
            SELECT step, from_state, to_state, action_taken
            FROM state_transitions
            WHERE session_id = ?
            ORDER BY step ASC
        """.trimIndent()).use { stmt ->
            stmt.setLong(1, sessionId)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                transitions.add(TransitionRecord(
                    step = rs.getInt("step"),
                    fromState = rs.getString("from_state"),
                    toState = rs.getString("to_state"),
                    actionTaken = rs.getString("action_taken") ?: ""
                ))
            }
        }
        return transitions
    }
}

// ── 데이터 클래스 ───────────────────────────────────────────────

data class ExperienceRecord(
    val sessionId: Long,
    val userCommand: String,
    val totalSteps: Int,
    val createdAt: String,
    val transitions: List<TransitionRecord>
)

data class TransitionRecord(
    val step: Int,
    val fromState: String,
    val toState: String,
    val actionTaken: String
)
