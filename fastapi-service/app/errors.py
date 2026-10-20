"""业务/参数错误：由 main.py 注册的 handler 转统一信封（与 Java ApiException 同形）。"""


class ApiError(Exception):
    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
