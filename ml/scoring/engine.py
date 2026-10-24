"""Детерминированная политика денежных потоков. Обучения и вероятности дефолта нет."""

import hashlib
import json
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR, localcontext
from pathlib import Path

from validation import validate_request, validator

ROOT = Path(__file__).resolve().parent
CENT = Decimal('0.01')
ALGORITHM = 'cashflow-v1'
PRODUCTS = {'WORKING_CAPITAL', 'OVERDRAFT', 'REVOLVING_LINE', 'LONG_TERM', 'GOVERNMENT_CONTRACT_LINE'}


def load_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def money_up(value):
    return value.quantize(CENT, rounding=ROUND_CEILING)


def money_text(value):
    # Для отрицательного диагностического остатка не скрываем дефицит округлением к нулю.
    return format(value.quantize(CENT, rounding=ROUND_FLOOR), '.2f')


def differentiated(amount, months, monthly_rate):
    regular_principal = money_up(amount / months)
    outstanding = amount
    payments = []
    for month in range(months):
        principal = outstanding if month == months - 1 else min(regular_principal, outstanding)
        payments.append(principal + money_up(outstanding * monthly_rate))
        outstanding -= principal
    return payments


def payment_scenario(features, product):
    amount = Decimal(features['amount'])
    months = int(features['termMonths'])
    annual_rate = Decimal(product['annualRatePercent']) / 100
    monthly_rate = annual_rate / 12
    if product['repayment'] == 'ANNUITY':
        payment = amount / months if monthly_rate == 0 else amount * monthly_rate / (1 - (1 + monthly_rate) ** (-months))
        return [money_up(payment)] * months, months, 'MONTHLY_ANNUITY'
    if product['repayment'] == 'OVERDRAFT':
        interest = money_up(amount * annual_rate * product['debtDays'] / product['dayCountBasis'])
        # Стресс-сценарий: новая полная выборка только после возврата предыдущей за 30 дней.
        return [amount + interest] * months, 1, 'FULL_LIMIT_EACH_30_DAY_CYCLE'
    if product['repayment'] == 'DIFFERENTIATED':
        tranche = int(features['trancheTermMonths'])
        payments = []
        while len(payments) < months:
            remaining = months - len(payments)
            duration = min(tranche, remaining)
            if duration < product['minTrancheMonths']:
                payments.extend([Decimal(0)] * remaining)
                break
            payments.extend(differentiated(amount, duration, monthly_rate))
        return payments, tranche, 'REPEATED_FULL_LIMIT_TRANCHES'
    if product['repayment'] == 'BULLET':
        interest = money_up(amount * monthly_rate)
        payments = [interest] * months
        payments[-1] += amount
        return payments, months, 'MONTHLY_INTEREST_PRINCIPAL_AT_END'
    raise ValueError('Неизвестная схема погашения')


class RuleEngine:
    def __init__(self, policy_path=None, catalog_path=None, schema_path=None):
        self.policy = load_json(policy_path or ROOT / 'policy.json')
        self.catalog = load_json(catalog_path or ROOT / 'catalog.json')
        schemas = load_json(schema_path or ROOT / 'contracts/schemas.json')
        self.validators = {name: validator(schemas, name) for name in ['ScoringRequest', 'ScoringResponse', 'ReadyView', 'LiveView', 'Problem']}
        self.stress = Decimal(self.policy['incomeStress'])
        self.safety = Decimal(self.policy['paymentSafetyFactor'])
        self.reserve_months = Decimal(self.policy['cashReserveMonths'])
        if self.policy['algorithmVersion'] != ALGORITHM or self.policy['featureSchemaVersion'] != '4':
            raise ValueError('Неподдерживаемая версия политики или схемы')
        if not all(x.is_finite() for x in [self.stress, self.safety, self.reserve_months]):
            raise ValueError('Неконечные параметры политики')
        if not 0 <= self.stress < 1 or self.safety < 1 or self.reserve_months < 0 or Decimal(self.policy['riskCutoff']) != Decimal('0.5'):
            raise ValueError('Недопустимые параметры политики')
        if set(self.catalog) != PRODUCTS:
            raise ValueError('Каталог должен поддерживать пять согласованных продуктов')
        for product in self.catalog.values():
            rate = Decimal(product['annualRatePercent'])
            minimum, maximum = Decimal(product['minAmount']), Decimal(product['maxAmount'])
            if not all(x.is_finite() for x in [rate, minimum, maximum]) or rate < 0 or not 0 < minimum <= maximum:
                raise ValueError('Некорректная ставка или диапазон сумм каталога')
            if not 1 <= product['minTermMonths'] <= product['maxTermMonths'] <= 600 or product['minimumBusinessAgeMonths'] < 0 or product['financialPeriodMonths'] not in [3, 6, 12]:
                raise ValueError('Некорректные сроки или период каталога')
            if product['repayment'] not in ['ANNUITY', 'OVERDRAFT', 'DIFFERENTIATED', 'BULLET']:
                raise ValueError('Неподдерживаемая механика продукта')
            if product['repayment'] == 'OVERDRAFT':
                if not 0 <= Decimal(product['inflowLimitFraction']) <= 1 or not isinstance(product['debtDays'], int) or not 1 <= product['debtDays'] <= 30 or not isinstance(product['dayCountBasis'], int) or product['dayCountBasis'] <= 0:
                    raise ValueError('Некорректные условия овердрафта')
            if product['repayment'] == 'DIFFERENTIATED' and not 3 <= product['minTrancheMonths'] <= product['maxTrancheMonths'] <= 6:
                raise ValueError('Некорректные сроки транша')
            if product['repayment'] == 'BULLET' and (not 0 <= Decimal(product['contractLimitFraction']) <= 1 or not isinstance(product['paymentGraceDays'], int) or not 0 <= product['paymentGraceDays'] <= 30):
                raise ValueError('Некорректные условия госконтракта')
        fingerprint = hashlib.sha256()
        fingerprint.update(json.dumps({'policy': self.policy, 'catalog': self.catalog, 'schemas': schemas}, sort_keys=True, separators=(',', ':')).encode())
        for path in [ROOT / 'engine.py', ROOT / 'validation.py']:
            fingerprint.update(path.read_text(encoding='utf-8').encode('utf-8'))
        self.model_version = 'rules-' + ALGORITHM + '-' + fingerprint.hexdigest()[:12]

    def ready(self):
        return {'status': 'READY', 'modelVersion': self.model_version, 'featureSchemaVersion': '4', 'supportedProductCodes': sorted(self.catalog)}

    def evaluate(self, request):
        validate_request(request, self.validators['ScoringRequest'], self.catalog)
        with localcontext() as context:
            context.prec = 50
            return self._evaluate(request)

    def _evaluate(self, request):
        f = request['features']
        product = self.catalog[f['productCode']]
        reasons = []
        # NO_HISTORY остаётся отдельным состоянием; null-суммы в исходном отчёте не изменяются.
        overdue = f['creditHistory']['status'] == 'AVAILABLE' and Decimal(f['creditHistory']['overdueDebtAmount']) > 0
        if overdue:
            reasons.append('CURRENT_OVERDUE_DEBT')
        if f['businessAgeMonths'] < product['minimumBusinessAgeMonths']:
            reasons.append('BUSINESS_TOO_YOUNG')
        inflow = Decimal(f['averageMonthlyInflow'])
        outflow = Decimal(f['averageMonthlyOutflow'])
        cash = Decimal(f['cashBalance'])
        stressed_inflow = inflow * (1 - self.stress)
        free_flow = stressed_inflow - outflow
        reserve = self.reserve_months * outflow
        balance = cash
        minimum = cash
        first_shortfall = 0 if cash < reserve else None
        timeline = []
        projected_shortfall = False
        payments, debt_months, scenario = payment_scenario(f, product)
        for month, payment in enumerate(payments, start=1):
            balance += free_flow - self.safety * payment
            minimum = min(minimum, balance)
            projected_shortfall |= balance < reserve
            if first_shortfall is None and balance < reserve:
                first_shortfall = month
            timeline.append({'month': month, 'newLoanPayment': money_text(payment), 'stressedBalance': money_text(balance)})
        if cash < reserve:
            reasons.append('INSUFFICIENT_INITIAL_LIQUIDITY')
        if projected_shortfall:
            reasons.append('INSUFFICIENT_PROJECTED_LIQUIDITY')
        passed = not reasons
        response = {'assessmentId': request['assessmentId'], 'featureSchemaVersion': '4', 'modelVersion': self.model_version, 'riskScore': 0.0 if passed else 1.0}
        self.validators['ScoringResponse'].validate(response)
        return {'response': response, 'decision': 'APPROVE' if passed else 'DECLINE',
                'reasonCodes': reasons or ['POLICY_RULES_SATISFIED'],
                'metrics': {'creditHistoryStatus': f['creditHistory']['status'], 'hasCurrentOverdue': overdue,
                            'debtRepaymentMonths': debt_months, 'assessmentMonths': len(payments), 'scenario': scenario,
                            'stressedMonthlyInflow': money_text(stressed_inflow), 'remainingMonthlyFlow': money_text(free_flow),
                            'requiredReserve': money_text(reserve), 'minimumStressedBalance': money_text(minimum),
                            'firstShortfallMonth': first_shortfall, 'payments': timeline}}
