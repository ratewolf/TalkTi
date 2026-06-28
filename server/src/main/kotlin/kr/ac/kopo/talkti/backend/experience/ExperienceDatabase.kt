package kr.ac.kopo.talkti.backend.experience

import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite 연결 및 테이블 초기화
 * DB 파일은 서버 실행 디렉토리에 experience.db로 생성됨
 */
object ExperienceDatabase {

    private const val DB_PATH = "./experience.db"
    private var connection: Connection? = null

    fun getConnection(): Connection {
        if (connection == null || connection!!.isClosed) {
            Class.forName("org.sqlite.JDBC")
            connection = DriverManager.getConnection("jdbc:sqlite:$DB_PATH")
            connection!!.autoCommit = true
            initTables()
        }
        return connection!!
    }

    private fun initTables() {
        val conn = connection!!
        conn.createStatement().use { stmt ->

            // 시나리오 분류 테이블
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS scenarios (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scenario_type TEXT NOT NULL UNIQUE,
                    keywords TEXT NOT NULL
                )
            """.trimIndent())

            // 세션 기록 테이블
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scenario_type TEXT NOT NULL,
                    user_command TEXT NOT NULL,
                    success INTEGER NOT NULL DEFAULT 0,
                    total_steps INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                )
            """.trimIndent())

            // 상태 전이 흐름 테이블
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS state_transitions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    step INTEGER NOT NULL,
                    from_state TEXT NOT NULL,
                    to_state TEXT NOT NULL,
                    action_taken TEXT,
                    FOREIGN KEY (session_id) REFERENCES sessions(id)
                )
            """.trimIndent())

            // 기본 시나리오 분류 데이터 삽입
            stmt.execute("""
                INSERT OR IGNORE INTO scenarios (scenario_type, keywords) VALUES
                ('길찾기', '어떻게가,길찾기,경로,가는길,가줘,데려다줘,네비,길안내'),
                ('카카오톡', '카톡,카카오톡,보내줘,메시지,사진보내'),
                ('앱실행', '열어줘,켜줘,실행,보여줘'),
                ('택시', '택시,카카오t,콜택시,불러줘'),
                ('키오스크', '키오스크,무인,주문,결제기,주문기,커피,음료,아메리카노,사줘,시켜줘')
            """.trimIndent())

            // 키오스크 시나리오에 성공 경험 데이터가 없는 경우에만 초기 시드 데이터 삽입
            val rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions WHERE scenario_type = '키오스크' AND success = 1")
            val hasKioskExperience = if (rs.next()) rs.getInt(1) > 0 else false
            rs.close()

            if (!hasKioskExperience) {
                // 1. 키오스크 세션 삽입
                stmt.execute("""
                    INSERT INTO sessions (scenario_type, user_command, success, total_steps) 
                    VALUES ('키오스크', '아메리카노 1잔 시켜줘', 1, 4)
                """.trimIndent())

                // 방금 삽입한 세션 ID 조회
                val idRs = stmt.executeQuery("SELECT last_insert_rowid()")
                val sessionId = if (idRs.next()) idRs.getLong(1) else -1L
                idRs.close()

                if (sessionId != -1L) {
                    // 2. 키오스크 세션의 성공적인 단계 흐름 삽입
                    stmt.execute("""
                        INSERT INTO state_transitions (session_id, step, from_state, to_state, action_taken) VALUES
                        ($sessionId, 1, 'IDLE', 'SELECT_TARGET', '화면의 아메리카노 메뉴 버튼 선택'),
                        ($sessionId, 2, 'SELECT_TARGET', 'SELECT_OPTION', '아이스/핫 선택 및 세부 옵션 지정'),
                        ($sessionId, 3, 'SELECT_OPTION', 'PRESS_ACTION', '주문담기 또는 바로 주문 버튼 선택'),
                        ($sessionId, 4, 'PRESS_ACTION', 'COMPLETE', '결제하기 및 카드 투입 안내 완료')
                    """.trimIndent())
                }
            }
        }
        println("[ExperienceDB] 테이블 초기화 완료")
    }

    fun close() {
        connection?.close()
        connection = null
    }
}
