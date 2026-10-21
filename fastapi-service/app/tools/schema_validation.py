"""Standard JSON Schema validation shared by Agent and MCP; never log argument values."""
from copy import deepcopy
from jsonschema import Draft202012Validator


def input_schema(definition: dict) -> dict:
    schema = deepcopy(definition.get("inputSchema") or {"type": "object"})
    if schema.get("type") == "object" and schema.get("properties"):
        schema["additionalProperties"] = False
    return schema


def argument_errors(definition: dict, arguments: object) -> list[str]:
    validator = Draft202012Validator(input_schema(definition))
    return sorted({"$." + ".".join(map(str, error.absolute_path)) + ":" + str(error.validator)
                   for error in validator.iter_errors(arguments)})[:10]
