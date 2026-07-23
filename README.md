# 말따라 프롬프터 (Speech-Following Teleprompter)

내 목소리를 **완전 오프라인 온디바이스 음성인식**으로 따라가며 대본을 자동 스크롤하는 안드로이드 방송용 프롬프터입니다. 한국어와 영어를 모두 인식합니다.

## 동작 원리

```
마이크(16kHz) → 에너지 게이트(침묵 제거) → SenseVoice int8 온디바이스 인식(0.5초 주기)
             → 인식 텍스트 ↔ 대본 바이그램 퍼지 매칭 → 현재 위치 추정 → 부드러운 자동 스크롤
```

- **음성인식**: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + SenseVoice 다국어 모델 (중/영/일/한/광둥어, int8 약 240MB). 인식 중 인터넷 불필요.
- **위치 추적**: 인식이 다소 틀려도 문자 바이그램 유사도로 "대본의 어디쯤인지"만 맞추므로 강건함. 읽은 부분은 흐리게 표시.
- **프롬프터 기능**: 가로 고정, 화면 꺼짐 방지, 미러 모드(하프미러 유리용), 폰트 크기 슬라이더, 처음부터 다시.

## 빌드 방법 A — GitHub Actions (PC 불필요, 권장)

이 저장소를 GitHub에 push하면 `.github/workflows/build-apk.yml`이 자동으로 APK를 빌드합니다.

- **폰에서 받기**: 저장소 → `Releases` → `latest` → `prompter-N.apk` 탭하면 바로 설치
- **Actions 탭에서 받기**: 실행 결과 하단 `prompter-apk` 아티팩트 (zip으로 감싸져 있음)
- 코드를 고쳐 push할 때마다 새 APK가 자동으로 만들어집니다
- 수동 실행: Actions 탭 → Build APK → `Run workflow`

빌드가 빨간불이면 해당 실행 로그의 `디버그 APK 빌드` 단계를 펼쳐 에러를 확인하세요.

## 빌드 방법 B — 로컬 Android Studio

1. Android Studio (Koala 이상)에서 이 폴더를 엽니다.
2. Gradle 동기화 → `Run` 또는 `Build > Build APK(s)`.
3. sherpa-onnx는 JitPack으로 받아옵니다. 실패하면
   [Releases](https://github.com/k2-fsa/sherpa-onnx/releases)에서 `sherpa-onnx-v1.12.x.aar`를 받아
   `app/libs/sherpa-onnx.aar`로 넣고 `app/build.gradle.kts`의 주석 처리된 줄로 교체하세요.

> 커맨드라인 빌드 시 gradle wrapper jar가 없으면 `gradle wrapper` 한 번 실행 후 `./gradlew assembleDebug`.

## 모델 준비 (최초 1회)

앱 첫 화면의 **모델 다운로드** 버튼을 누르면 됩니다. 수동으로 넣으려면:

```bash
# https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
adb push model.int8.onnx tokens.txt /sdcard/Android/data/com.prompter.app/files/model/
```

다운로드 URL이 바뀌었다면 `ModelManager.kt`의 `BASE` 상수를 수정하세요.

## 사용법

1. 대본 붙여넣기 → **프롬프터 시작**
2. `▶ 시작`을 누르고 대본을 읽기 시작하면 자동으로 따라 스크롤됩니다.
3. 대사를 건너뛰어도 앞쪽 일정 범위 안이면 따라잡습니다. 우하단 회색 글씨는 인식 디버그 표시.

## 튜닝 포인트

| 상황 | 수정 위치 |
|---|---|
| 조용한 목소리를 못 잡음 | `AsrEngine.ENERGY_GATE` 낮추기 (0.012 → 0.008) |
| 스크롤이 너무 민감/둔감 | `ScriptMatcher.THRESHOLD` (0.44) 조정 |
| 애드리브가 많음 | `ScriptMatcher.LOOKAHEAD` 늘리기 |
| 저사양 폰에서 버벅임 | `AsrEngine.DECODE_EVERY_MS` 700~1000으로 |

## 알려진 한계

- 한 문장 안에서 한/영이 심하게 섞이면 인식률이 떨어질 수 있음 (위치 추적은 대체로 유지됨)
- 첫 디코딩 시 모델 로딩에 몇 초 소요
- arm64-v8a만 포함 (에뮬레이터는 `abiFilters`에 `x86_64` 추가)

## 라이선스

MIT (sherpa-onnx는 Apache-2.0, SenseVoice 모델은 해당 모델 라이선스를 따름)
