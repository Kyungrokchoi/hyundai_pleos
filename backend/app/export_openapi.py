"""Write the OpenAPI contract: python -m app.export_openapi [contracts/openapi.yaml | -]"""
import sys

import yaml

from app.main import app

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "-"
    text = yaml.safe_dump(app.openapi(), allow_unicode=True, sort_keys=False)
    if out == "-":
        sys.stdout.write(text)
    else:
        open(out, "w", encoding="utf-8").write(text)
