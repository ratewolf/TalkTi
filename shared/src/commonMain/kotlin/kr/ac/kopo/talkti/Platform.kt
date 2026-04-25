package kr.ac.kopo.talkti

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform