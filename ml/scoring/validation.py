"""Проверка JSON-контракта и согласованности денежных/календарных полей."""

import calendar
from datetime import date, timedelta
from decimal import Decimal, ROUND_DOWN

from jsonschema import Draft202012Validator, FormatChecker


class InvalidFeatures(ValueError):
    def __init__(self, issues):
        super().__init__('Признаки не соответствуют контракту')
        self.issues = issues[:30]


def validator(schemas, name):
    schema = {'$schema': schemas['$schema'], '$defs': schemas['$defs'], '$ref': '#/$defs/' + name}
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def add_months(value, months):
    year, month = divmod(value.year * 12 + value.month - 1 + int(months), 12)
    month += 1
    if not 1 <= year <= 9999:
        raise InvalidFeatures([issue('features', 'INVALID_DATE_RANGE', 'Период выходит за поддерживаемый календарь')])
    return date(year, month, min(value.day, calendar.monthrange(year, month)[1]))


def issue(field, code, message):
    return {'field': field, 'code': code, 'message': message}


def validate_request(document, request_validator, catalog):
    issues = []
    for error in request_validator.iter_errors(document):
        path = '.'.join(map(str, error.absolute_path))
        if error.validator == 'required' and isinstance(error.instance, dict):
            for key in error.validator_value:
                if key not in error.instance:
                    issues.append(issue('.'.join(filter(None, [path, key])), 'REQUIRED', 'Обязательное поле отсутствует'))
        else:
            message = 'Неверный тип, формат или значение поля'
            if path == 'featureSchemaVersion':
                message = 'Нужна схема 4 и обязательный features.cashBalance; схема 3 больше не поддерживается'
            issues.append(issue(path or 'request', 'INVALID_FORMAT', message))
        if len(issues) >= 30:
            break
    if issues:
        raise InvalidFeatures(issues)

    f = document['features']
    product = catalog.get(f['productCode'])
    if not product:
        raise InvalidFeatures([issue('features.productCode', 'UNSUPPORTED_PRODUCT', 'Продукт не поддерживается этой версией правил')])
    amount = Decimal(f['amount'])
    if not Decimal(product['minAmount']) <= amount <= Decimal(product['maxAmount']):
        issues.append(issue('features.amount', 'OUT_OF_RANGE', 'Сумма вне диапазона продукта'))
    if not product['minTermMonths'] <= f['termMonths'] <= product['maxTermMonths']:
        issues.append(issue('features.termMonths', 'OUT_OF_RANGE', 'Срок не соответствует продукту'))
    if f['reportingPeriod']['months'] != product['financialPeriodMonths']:
        issues.append(issue('features.reportingPeriod.months', 'INVALID_PERIOD', 'Период не соответствует продукту'))

    as_of = date.fromisoformat(f['asOfDate'])
    period = f['reportingPeriod']
    start, end = date.fromisoformat(period['startDate']), date.fromisoformat(period['endDate'])
    expected_end = add_months(start, period['months']) - timedelta(days=1)
    if start.day != 1 or end != expected_end or end >= as_of.replace(day=1):
        issues.append(issue('features.reportingPeriod', 'INVALID_PERIOD', 'Нужны полные календарные месяцы до месяца рассмотрения'))

    history = f['creditHistory']
    history_date = date.fromisoformat(history['asOfDate'])
    hp = history['delinquencyPeriod']
    expected_start = add_months(history_date.replace(day=1), -12)
    expected_history_end = history_date.replace(day=1) - timedelta(days=1)
    if hp['startDate'] != expected_start.isoformat() or hp['endDate'] != expected_history_end.isoformat():
        issues.append(issue('features.creditHistory.delinquencyPeriod', 'INVALID_PERIOD', 'Нужны 12 полных месяцев до месяца кредитного отчёта'))
    if history_date > as_of:
        issues.append(issue('features.creditHistory.asOfDate', 'FUTURE_DATE', 'Дата отчёта не может быть позже даты рассмотрения'))
    if history['status'] == 'AVAILABLE' and Decimal(history['overdueDebtAmount']) > Decimal(history['totalOutstandingDebt']):
        issues.append(issue('features.creditHistory.overdueDebtAmount', 'INCONSISTENT_DEBT', 'Просроченная часть не может превышать общий долг'))

    if f['productCode'] == 'OVERDRAFT':
        cap = (Decimal(product['inflowLimitFraction']) * Decimal(f['averageMonthlyInflow'])).quantize(Decimal('0.01'), rounding=ROUND_DOWN)
        if amount > cap:
            issues.append(issue('features.amount', 'DYNAMIC_LIMIT_EXCEEDED', 'Превышен предел по среднемесячным поступлениям'))
    if f['productCode'] == 'REVOLVING_LINE':
        if not product['minTrancheMonths'] <= f['trancheTermMonths'] <= product['maxTrancheMonths']:
            issues.append(issue('features.trancheTermMonths', 'OUT_OF_RANGE', 'Срок транша вне условий продукта'))
    if f['productCode'] == 'GOVERNMENT_CONTRACT_LINE':
        government = f['governmentContract']
        cost, advance = Decimal(government['contractAmount']), Decimal(government['advanceAmount'])
        if advance > cost:
            issues.append(issue('features.governmentContract.advanceAmount', 'OUT_OF_RANGE', 'Аванс превышает стоимость контракта'))
        else:
            cap = ((cost - advance) * Decimal(product['contractLimitFraction'])).quantize(Decimal('0.01'), rounding=ROUND_DOWN)
            if amount > cap:
                issues.append(issue('features.amount', 'DYNAMIC_LIMIT_EXCEEDED', 'Превышен предел по стоимости контракта за вычетом аванса'))
        execution = date.fromisoformat(government['executionEndDate'])
        final_payment = date.fromisoformat(government['finalPaymentDate'])
        planned = date.fromisoformat(government['plannedEndDate'])
        if planned != add_months(as_of, f['termMonths']):
            issues.append(issue('features.governmentContract.plannedEndDate', 'INCONSISTENT_DATE', 'Плановый конец не соответствует дате рассмотрения и сроку'))
        grace = timedelta(days=product['paymentGraceDays'])
        if date.max - final_payment < grace:
            issues.append(issue('features.governmentContract.finalPaymentDate', 'INVALID_DATE_RANGE', 'Дата оплаты выходит за поддерживаемый календарь'))
        elif execution < as_of or execution > planned or planned > final_payment + grace:
            issues.append(issue('features.governmentContract', 'INVALID_DATES', 'Даты исполнения, оплаты и возврата не соответствуют условиям продукта'))
    if issues:
        raise InvalidFeatures(issues)
