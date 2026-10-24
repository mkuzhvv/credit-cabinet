"""Однократная подготовка токенизатора и проверка локальной модели."""

import hashlib
import json
import os
from urllib.request import urlopen

from assistant import ROOT, config, verify_model


def main():
    settings = config()
    metadata_path = ROOT / settings.get("tokenizer_metadata_path", "tokenizer_metadata.json")
    if metadata_path.exists():
        metadata = json.loads(metadata_path.read_text())
        revision = metadata["revision"]
    else:
        with urlopen("https://huggingface.co/api/models/" + settings["tokenizer_repo"], timeout=30) as response:
            revision = json.load(response)["sha"]
        metadata = None
    url = f"https://huggingface.co/{settings['tokenizer_repo']}/resolve/{revision}/tokenizer.json"
    target = ROOT / settings["tokenizer_path"]
    if not target.exists():
        target.parent.mkdir(exist_ok=True)
        with urlopen(url, timeout=90) as response:
            content = response.read()
        checksum = hashlib.sha256(content).hexdigest()
        if metadata and checksum != metadata["sha256"]:
            raise ValueError("Контрольная сумма загруженного токенизатора не совпадает")
        target.write_bytes(content)
    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    if metadata and checksum != metadata["sha256"]:
        raise ValueError("Контрольная сумма существующего токенизатора не совпадает")
    metadata_path.write_text(json.dumps({"repository": settings["tokenizer_repo"], "revision": revision,
                                        "sha256": checksum, "url": url}, indent=2))
    model = verify_model(os.getenv("OLLAMA_BASE_URL", "http://localhost:11434"))
    print(f"Готово: локальная {model['name']}, токенизатор закреплён по SHA-256.")


if __name__ == "__main__":
    main()
