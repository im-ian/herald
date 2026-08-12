# Herald webhook v1

## Request

- Method: `POST`
- Content-Type: `application/json; charset=utf-8`
- Accept: `application/json`
- Idempotency-Key: body의 `id`와 같은 값
- Authorization: token을 설정한 경우 `Bearer <token>`
- Redirect: 따르지 않음
- Connect timeout: 10초
- Read timeout: 15초

Body의 epoch timestamp는 모두 milliseconds 단위입니다. Android 알림이 값을 제공하지 않은 필드는 `null`입니다. `capturedAt`은 Herald가 알림을 관찰한 시각이며 이벤트 ID 계산에 포함되지 않습니다.

`extractionMethod` 값:

- `messaging_style`: Android의 구조화된 메시지
- `text`: `Notification.EXTRA_TEXT`
- `big_text`: text가 없을 때 `Notification.EXTRA_BIG_TEXT`
- `text_lines`: 위 값들이 없을 때 마지막 `Notification.EXTRA_TEXT_LINES` 항목

`contentTruncated`가 `true`이면 안전한 처리 한도를 넘은 문자열 또는 메시지 목록을 Herald가 잘랐다는 의미입니다. 첨부 URI는 의도적으로 전송하지 않습니다.

## Response and retries

| Response | Herald behavior |
| --- | --- |
| `2xx` | 성공 처리 후 로컬 민감 본문 삭제 |
| `408`, `425`, `429` | 지수 backoff로 재시도 |
| `5xx` | 지수 backoff로 재시도 |
| other `3xx`/`4xx` | 실패 보관, 화면에서 수동 재시도 가능 |
| network failure | 최대 10회 재시도 후 실패 보관 |

WorkManager 전달은 **at-least-once**입니다. 서버가 응답한 직후 앱 프로세스가 종료되면 같은 이벤트가 다시 전송될 수 있으므로, 서버는 `Idempotency-Key` 또는 body의 `id`에 unique constraint를 두어야 합니다.

전달 대기 중인 이벤트는 7일/500건 기록 정리에서 제외됩니다. 네트워크 실패는 최대 10회 자동 재시도한 뒤 `FAILED`가 되며, 이후에는 사용자가 확인하고 재시도해야 합니다.

## Configuration changes

- 웹훅이 비어 있을 때 수집된 이벤트는 `LOCAL`이며 나중에 자동 전송하지 않습니다.
- `PENDING` 이벤트는 수집 시점의 endpoint와 Bearer token 조합에 묶입니다. 둘 중 하나가 바뀌면 자동으로 새 라우트에 보내지 않고 `FAILED`로 전환합니다.
- 새 endpoint 또는 token을 확인한 뒤 **실패 재시도**를 누르면 실패 이벤트를 현재 라우트에 명시적으로 다시 연결합니다.
- 성공한 이벤트의 민감 필드는 재전송할 수 없도록 즉시 지워집니다.
- **기록 삭제**는 예약된 전달 작업을 먼저 취소하지만, 이미 네트워크로 전송되기 시작한 HTTP 요청은 회수할 수 없습니다.

## Local HTTP

로컬 개발용 옵션을 명시적으로 켠 경우에만 정확한 `localhost` 또는 숫자로 표기한 loopback/사설 IP에 HTTP를 사용할 수 있습니다. DNS 호스트명과 공인 IP는 허용하지 않으며, 평문 유출을 막기 위해 HTTP endpoint에는 Bearer token을 설정할 수 없습니다.
