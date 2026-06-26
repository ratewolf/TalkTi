#!/bin/bash

# 8080 포트를 점유하고 있는 프로세스 ID(PID) 조회
PID=$(lsof -t -i:8080)

if [ -z "$PID" ]; then
    echo "ℹ️ 현재 실행 중인 TalkTi 서버 프로세스가 없습니다."
else
    echo "🛑 포트 8080에서 실행 중인 TalkTi 서버(PID: $PID)를 종료합니다..."
    kill -15 $PID
    
    # 프로세스가 완전히 죽을 때까지 최대 5초 대기
    for i in {1..5}; do
        if ! ps -p $PID > /dev/null; then
            echo "✅ TalkTi 서버가 정상적으로 종료되었습니다."
            exit 0
        fi
        sleep 1
    done
    
    # 정상 종료 실패 시 강제 종료
    echo "⚠️ 정상 종료가 지연되어 강제 종료(kill -9)를 수행합니다..."
    kill -9 $PID
    echo "✅ TalkTi 서버가 강제 종료되었습니다."
fi
