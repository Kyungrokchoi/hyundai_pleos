from fastapi import HTTPException


class ApiError(HTTPException):

    def __init__(self, status: int, code: str, message_ko: str, detail: str | None = None):
        super().__init__(status_code=status, detail={"code": code, "message_ko": message_ko, "detail": detail})


def not_found(what: str = "대상") -> ApiError:
    return ApiError(404, "NOT_FOUND", "찾을 수 없거나 볼 수 있는 권한이 없습니다.")


def forbidden(msg: str = "이 작업을 할 권한이 없습니다.") -> ApiError:
    return ApiError(403, "FORBIDDEN", msg)


def conflict(code: str, msg: str) -> ApiError:
    return ApiError(409, code, msg)
