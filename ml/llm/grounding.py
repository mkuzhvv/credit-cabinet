"""Факты из текущего каталога. Здесь нет собственной таблицы ставок и лимитов."""

import re
from decimal import Decimal, ROUND_DOWN

NUMBER = r"\d+(?:[ \u00a0]\d{3})*(?:[.,]\d+)?"
WORDS = {"один": 1, "одну": 1, "два": 2, "две": 2, "три": 3, "четыре": 4,
         "пять": 5, "шесть": 6, "семь": 7, "восемь": 8, "девять": 9, "десять": 10}
VERSION = "facts-v3"


def decimal(text):
    return Decimal(text.replace(" ", "").replace("\u00a0", "").replace(",", ".").replace("−", "-"))


def display(value):
    text = format(value, "f")
    whole, dot, fraction = text.partition(".")
    whole = re.sub(r"(?<=\d)(?=(\d{3})+$)", " ", whole)
    fraction = fraction.rstrip("0")
    return whole + ("," + fraction if fraction else "")


def month_word(count):
    if 11 <= count % 100 <= 14:
        return "месяцев"
    return "месяц" if count % 10 == 1 else "месяца" if count % 10 in [2, 3, 4] else "месяцев"


def loan_term(product):
    for condition in product["conditions"]:
        match = re.search(r"срок[^\d]{0,60}(\d+)\s*[–-]\s*(\d+)\s*месяц", condition, re.I)
        if match:
            return int(match[1]), int(match[2])
        match = re.search(r"срок[^\d]{0,60}(\d+)\s*месяц", condition, re.I)
        if match:
            return int(match[1]), int(match[1])
    return None


def desired_term(message):
    match = re.search(r"\bна\s+(\d+|" + "|".join(WORDS) + r")\s+(лет|год\w*|месяц\w*)", message, re.I)
    if not match:
        return None
    word = match[1].lower()
    count = int(word) if word.isdigit() else WORDS[word]
    return count * 12 if match[2].lower().startswith(("год", "лет")) else count


def annual_rate(product):
    for condition in product["conditions"]:
        match = re.search(r"ставк[^\d]{0,60}(\d+(?:[.,]\d+)?)\s*(?:%|процент)", condition, re.I)
        if not match:
            match = re.search(r"(\d+(?:[.,]\d+)?)\s*%\s*годов", condition, re.I)
        if match:
            return decimal(match[1])
    return None


def term_text(bounds):
    low, high = bounds
    return str(low) if low == high else f"{low}–{high}"


def recommendation(request, reply):
    catalog = request["context"]["catalog"]
    product = next(p for p in catalog if p["code"] == reply["recommendedProductCode"])
    bounds = loan_term(product)
    wanted = desired_term(request["message"])
    if bounds and wanted and not bounds[0] <= wanted <= bounds[1]:
        all_terms = [loan_term(p) for p in catalog]
        no_term = all(b and not b[0] <= wanted <= b[1] for b in all_terms)
        text = (f"В переданном каталоге нет продукта на запрошенный срок {wanted} {month_word(wanted)}. " if no_term else "")
        text += f"У продукта «{product['name']}» срок {term_text(bounds)} {month_word(bounds[1])}, поэтому на указанный срок я его не предлагаю."
        return {"answer": text, "recommendedProductCode": None}, "incompatible_term"

    rate = annual_rate(product)
    text = f"Можно предварительно рассмотреть «{product['name']}»"
    text += f" под {display(rate)}% годовых" if rate is not None else ""
    if bounds:
        duration = str(wanted) if wanted else term_text(bounds)
        text += f" на {duration} {month_word(wanted or bounds[1])}"
    text += "."
    if rate is None:
        text += " Годовая ставка в переданных условиях не указана."
    purpose = product["purpose"].strip().rstrip(".")
    if len(purpose) < 800:
        text += " " + purpose + "."
    for condition in product["conditions"]:
        first = condition.split(". ")[0].rstrip(".")
        if re.match(r"нужн", first, re.I) and "цель" in first.lower() and "бюджет" in first.lower():
            text += " " + first + "."
            break
    if product["financingType"] == "OVERDRAFT":
        debt = re.search(r"за\s+(\d+)\s+дн", " ".join(product["conditions"]), re.I)
        if debt:
            text += f" Срок возврата долга — {debt[1]} дней с первого использования; это отдельный срок от срока лимита."
    return {"answer": text, "recommendedProductCode": product["code"]}, "catalog_recommendation"


def amount_cap(product):
    for condition in product["conditions"]:
        if not re.search(r"сумм|лимит", condition, re.I):
            continue
        match = re.search(rf"({NUMBER})\s*(?:[–-]|до)\s*({NUMBER})\s*(?:RUB|руб\w*|₽)", condition, re.I)
        if match:
            return decimal(match[2])
    return None


def money_values(message):
    values = []
    for match in re.finditer(rf"([−-]?{NUMBER})\s*(млн|миллион\w*|тыс\.?|тысяч\w*)?", message, re.I):
        tail = message[match.end():].lstrip()
        if not match[2] and re.match(r"месяц|год|лет|дн|недел|%|процент", tail, re.I):
            continue
        value = decimal(match[1])
        unit = (match[2] or "").lower()
        if unit.startswith(("млн", "миллион")):
            value *= 1000000
        elif unit.startswith("тыс"):
            value *= 1000
        values.append(value)
    return values


def limit_calculation(request):
    message = request["message"].lower()
    if not re.search(r"лимит|предел|максим|рассчит", message):
        return None
    code = "OVERDRAFT" if "овердрафт" in message else "GOVERNMENT_CONTRACT_LINE" if "контракт" in message and "аванс" in message else None
    product = next((p for p in request["context"]["catalog"] if p["code"] == code), None)
    if not product:
        return None
    conditions = " ".join(product["conditions"])
    percent = re.search(r"не\s+больше\s+(\d+(?:[.,]\d+)?)\s*%", conditions, re.I)
    cap = amount_cap(product)
    values = money_values(message)
    if not percent or cap is None:
        return None
    percent = decimal(percent[1])
    if code == "OVERDRAFT" and len(values) == 1 and values[0] < 0:
        return {"answer": "Укажите неотрицательную сумму среднемесячных поступлений для расчёта предела.", "recommendedProductCode": None}, "invalid_amount"
    if code == "OVERDRAFT" and len(values) == 1 and values[0] >= 0:
        base = values[0]
        share = base * percent / 100
        formula = f"{display(percent)}% × {display(base)} ₽ = {display(share)} ₽"
    elif code == "GOVERNMENT_CONTRACT_LINE" and len(values) == 2:
        # Не угадываем порядок чисел: стоимость и аванс должны быть явно подписаны.
        contract = re.search(rf"контракт[^\d]{{0,25}}({NUMBER})\s*(млн|миллион\w*|тыс\.?|тысяч\w*)?", message)
        advance = re.search(rf"аванс[^\d]{{0,25}}({NUMBER})\s*(млн|миллион\w*|тыс\.?|тысяч\w*)?", message)
        if not contract or not advance or not re.search(r"стоимост.*[−-].*аванс", conditions, re.I):
            return None
        cost = money_values(contract.group())[0]
        paid = money_values(advance.group())[0]
        if cost <= 0 or paid < 0 or paid > cost:
            return None
        share = (cost - paid) * percent / 100
        formula = f"{display(percent)}% × ({display(cost)} − {display(paid)}) ₽"
    else:
        return None
    result = min(cap, share).quantize(Decimal("0.01"), rounding=ROUND_DOWN)
    text = f"Расчётный предел — {display(result)} ₽: {formula}, при потолке {display(cap)} ₽. Остальные условия продукта сохраняются; это не одобрение."
    return {"answer": text, "recommendedProductCode": None}, "source_formula"


def field_explanation(request):
    message = request["message"].lower()
    data = request["context"]
    if re.search(r"отправ|измени|скры|придум|гарантир", message):
        return None
    if "период" in message and re.search(r"средн|счит|расч", message):
        code = data["selectedProductCode"] or (data["application"]["productCode"] if data["application"] else None)
        products = [p for p in data["catalog"] if not code or p["code"] == code]
        text = "; ".join(f"«{p['name']}» — {p['financialPeriodMonths']} {month_word(p['financialPeriodMonths'])}" for p in products)
        return {"answer": "Период для среднемесячных показателей зависит от продукта: " + text + ".", "recommendedProductCode": None}, "catalog_periods"
    asks_field = re.search(r"включ|учит|среднемесяч|впис|заполн|в этом поле", message)
    if not asks_field:
        return None
    code = "form.averageMonthlyOutflow" if re.search(r"расход|затрат|трат", message) else "form.averageMonthlyInflow" if "поступлен" in message else None
    definition = next((f for f in data["fieldDefinitions"] if f["code"] == code), None)
    if not definition:
        return None
    text = definition["description"].replace("reportingPeriod / months", "выбранный период, делённые на число месяцев")
    text = text.replace("reportingPeriod", "выбранный период").replace("его months", "число месяцев").replace("months", "число месяцев")
    text = f"{definition['label']}: {text}"
    if data["application"]:
        text += f" В вашей заявке период — {data['application']['reportingPeriod']['months']} {month_word(data['application']['reportingPeriod']['months'])}."
    return {"answer": text, "recommendedProductCode": None}, "field_definition"


def ground_reply(request, reply):
    calculation = limit_calculation(request)
    if calculation:
        return calculation
    if reply["recommendedProductCode"] is not None:
        return recommendation(request, reply)
    explanation = field_explanation(request)
    if explanation:
        return explanation
    return reply, "model_text"
