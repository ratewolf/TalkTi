#!/bin/bash

# 실행 경로 설정
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SERVER_DIR"

# 로그 저장용 logs 폴더 생성
mkdir -p logs

# 기존에 실행 중인 서버 종료
echo "🛑 기존에 실행 중인 TalkTi 서버를 먼저 종료합니다..."
./stop.sh

# Ktor FatJar 빌드 (서버 실행에 필요한 모든 의존성이 포함된 단일 Jar 생성)
echo "🔨 TalkTi 서버 빌드 중..."
../gradlew :server:buildFatJar -x test

# 빌드 성공 여부 확인
if [ $? -eq 0 ]; then
    JAR_FILE=$(find build/libs -name "*.jar" | head -n 1)
    if [ -f "$JAR_FILE" ]; then
        echo "🚀 TalkTi 백엔드 서버를 백그라운드(nohup)로 실행합니다... (실행 파일: $JAR_FILE)"
        # nohup으로 백그라운드 실행 및 로그 리다이렉션
        nohup java -jar "$JAR_FILE" > logs/nohup.out 2>&1 &
        
        sleep 2
        echo "✅ TalkTi 서버가 성공적으로 백그라운드에서 실행되었습니다!"
        echo "👀 실시간 서버 로그를 보려면 아래 명령어를 입력하세요:"
        echo "   tail -f logs/talkti-server.log"
    else
        echo "❌ 빌드는 성공했으나 실행할 Jar 파일을 찾을 수 없습니다."
    fi
else
    echo "❌ 빌드에 실패했습니다. 코드를 확인해 주세요."
fi
