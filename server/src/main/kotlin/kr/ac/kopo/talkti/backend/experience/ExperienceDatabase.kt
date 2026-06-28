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
                ('택시', '택시,카카오t,콜택시,불러줘')
            """.trimIndent())
        }
        println("[ExperienceDB] 테이블 초기화 완료")
    }

    fun close() {
        connection?.close()
        connection = null
    }
}
