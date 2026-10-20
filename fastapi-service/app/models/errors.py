"""模型调用异常：对外只暴露固定文案，避免泄露供应商响应或密钥信息。"""


class ModelProviderError(Exception):
    """模型不可用、响应不合法或工具参数无法解析。"""

    def __init__(self, message: str = "智能编排暂不可用，请稍后重试") -> None:
        super().__init__(message)
        self.message = message
