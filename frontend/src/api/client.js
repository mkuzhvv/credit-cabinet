const BASE_URL = '/api/v1';

let apiMode = localStorage.getItem('alfa_api_mode') || 'mock';
let csrfToken = null;
let sessionPromise = null;
const pendingCommands = new Map();
let csrfHeaderName = 'X-CSRF-TOKEN';

export function getApiMode() {
  return apiMode;
}

export function setApiMode(mode) {
  apiMode = mode;
  sessionPromise = null;
  csrfToken = null;
  localStorage.setItem('alfa_api_mode', mode);
}

export function generateUUID() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function toMoneyString(value) {
  if (value === null || value === undefined || String(value).trim() === '') {
    return null;
  }
  const cleaned = String(value).replace(/\s/g, '').replace(',', '.');
  if (!/^\d+(?:\.\d{1,2})?$/.test(cleaned)) return cleaned;
  const [whole, fraction = ''] = cleaned.split('.');
  return `${whole.replace(/^0+(?=\d)/, '')}.${fraction.padEnd(2, '0')}`;
}

export function formatRubles(moneyStr) {
  if (moneyStr === null || moneyStr === undefined || moneyStr === '') return '—';
  const num = Number(moneyStr);
  if (Number.isNaN(num)) return moneyStr;
  return new Intl.NumberFormat('ru-RU', {
    style: 'currency',
    currency: 'RUB',
    minimumFractionDigits: Number.isInteger(num) ? 0 : 2,
    maximumFractionDigits: 2,
  }).format(num);
}

export const MOCK_PRODUCTS = [
  {
    code: 'WORKING_CAPITAL',
    name: 'Оборотный кредит',
    description:
      'Подойдёт, если нужно закупить товары или сырьё, оплатить аренду, услуги или другие расходы бизнеса. Вы получаете всю сумму сразу и возвращаете её по графику.',
    terms: {
      version: 'demo-working-capital-2',
      currency: 'RUB',
      minAmount: '100000.00',
      maxAmount: '3000000.00',
      minTermMonths: 3,
      maxTermMonths: 12,
      annualRatePercent: '24.00',
      minBusinessAgeMonths: 6,
      disbursementDescription: 'Вся сумма сразу одной выплатой.',
      repaymentDescription: 'Ежемесячно равными платежами.',
    },
  },
  {
    code: 'OVERDRAFT',
    name: 'Овердрафт',
    description:
      'Подойдёт, если платёж нужно провести сейчас, а деньги от покупателей поступят позже. Банк подключает лимит к расчётному счёту: вы используете его при недостатке собственных средств.',
    terms: {
      version: 'demo-overdraft-2',
      currency: 'RUB',
      minAmount: '50000.00',
      maxAmount: '1000000.00',
      minTermMonths: 12,
      maxTermMonths: 12,
      annualRatePercent: '26.00',
      minBusinessAgeMonths: 6,
      disbursementDescription:
        'Лимит к расчётному счёту (до 30% среднемесячных поступлений за последние 3 мес.).',
      repaymentDescription:
        'Автоматически из поступлений на счёт (полное погашение — в течение 30 дней с первого использования).',
    },
  },
  {
    code: 'REVOLVING_LINE',
    name: 'Возобновляемая кредитная линия',
    description:
      'Подойдёт, если финансирование требуется неоднократно, а размер и даты расходов меняются. Банк одобряет общий лимит, из которого вы получаете нужные суммы.',
    terms: {
      version: 'demo-revolving-line-2',
      currency: 'RUB',
      minAmount: '300000.00',
      maxAmount: '5000000.00',
      minTermMonths: 24,
      maxTermMonths: 24,
      annualRatePercent: '23.00',
      minBusinessAgeMonths: 12,
      disbursementDescription:
        'Получение денег частями (траншами на 3–6 мес.); погашенная часть долга снова доступна.',
      repaymentDescription:
        'По каждой полученной сумме — ежемесячно равными долями основного долга плюс проценты.',
    },
  },
  {
    code: 'LONG_TERM',
    name: 'Кредит на длительный срок',
    description:
      'Подойдёт для расходов, результат от которых бизнес будет получать несколько лет: покупки оборудования, ремонта помещения или открытия дополнительной точки.',
    terms: {
      version: 'demo-long-term-2',
      currency: 'RUB',
      minAmount: '500000.00',
      maxAmount: '5000000.00',
      minTermMonths: 24,
      maxTermMonths: 60,
      annualRatePercent: '22.00',
      minBusinessAgeMonths: 12,
      disbursementDescription: 'Вся сумма сразу одной выплатой.',
      repaymentDescription: 'Ежемесячно равными платежами.',
    },
  },
  {
    code: 'GOVERNMENT_CONTRACT_LINE',
    name: 'Кредитная линия на госконтракт',
    description:
      'Подойдёт, если вы уже заключили государственный контракт и нужны деньги на товары, материалы или работы для его исполнения. Средства выдаются частями под расходы по контракту.',
    terms: {
      version: 'demo-government-contract-line-2',
      currency: 'RUB',
      minAmount: '300000.00',
      maxAmount: '10000000.00',
      minTermMonths: 1,
      maxTermMonths: 12,
      annualRatePercent: '21.00',
      minBusinessAgeMonths: 12,
      disbursementDescription:
        'Частями в пределах лимита (до 70% суммы контракта за вычетом аванса). Лимит не возобновляется.',
      repaymentDescription:
        'Проценты — ежемесячно; поступления по контракту направляются на погашение долга (не позднее 30 дней после даты оплаты).',
    },
  },
];

const PRODUCT_RULES = {
  WORKING_CAPITAL: { financialPeriodMonths: 6, financingType: 'TERM_LOAN', repaymentType: 'ANNUITY' },
  OVERDRAFT: { financialPeriodMonths: 3, financingType: 'OVERDRAFT', repaymentType: 'ACCOUNT_SWEEP',
    overdraftRules: { maxInflowSharePercent: '30.00', maxDebtPeriodDays: 30 } },
  REVOLVING_LINE: { financialPeriodMonths: 6, financingType: 'REVOLVING_LINE', repaymentType: 'EQUAL_PRINCIPAL_PER_TRANCHE',
    revolvingLineRules: { minTrancheTermMonths: 3, maxTrancheTermMonths: 6 } },
  LONG_TERM: { financialPeriodMonths: 12, financingType: 'TERM_LOAN', repaymentType: 'ANNUITY' },
  GOVERNMENT_CONTRACT_LINE: { financialPeriodMonths: 12, financingType: 'NON_REVOLVING_LINE', repaymentType: 'CONTRACT_PROCEEDS',
    governmentContractRules: { maxNetContractSharePercent: '70.00', maxDaysAfterFinalPayment: 30, renewable: false } },
};

MOCK_PRODUCTS.forEach(product => Object.assign(product.terms, {
  requiresSettlementAccount: product.code === 'OVERDRAFT',
  requiresRegularInflow: product.code === 'OVERDRAFT',
  overdraftRules: null, revolvingLineRules: null, governmentContractRules: null,
  generalConditions: 'Учебный проект. Условия демонстрационные. Залог, поручительство и обязательная страховка не требуются. Комиссий нет. Отсутствие текущей просрочки обязательно.',
  ...PRODUCT_RULES[product.code],
}));

export function storageKey(name) {
  return `alfa_${apiMode}_${name}`;
}

const MOCK_USER = {
  user: {
    id: 'b827db91-df42-4d75-b5e0-8f0a7a512ee1',
    displayName: 'Демо-представитель',
  },
  company: {
    id: '97daa92f-127c-4f70-8a0c-0711f13c62ef',
    name: 'Демо-пекарня',
    legalForm: 'OOO',
    registrationDate: '2019-08-01',
    countryCode: 'RU',
    hasSettlementAccount: true,
    hasRegularInflowLast3Months: true,
  },
  demo: true,
};

function loadMockApps() {
  try {
    const raw = localStorage.getItem('alfa_mock_applications_v2');
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function saveMockApps(apps) {
  localStorage.setItem('alfa_mock_applications_v2', JSON.stringify(apps));
}

function computeAllowedActions(status) {
  if (status === 'DRAFT' || status === 'NEEDS_INFO') return ['UPDATE_FORM', 'SUBMIT'];
  if (status === 'TECHNICAL_HOLD') return ['RETRY'];
  return [];
}

function evaluateMockApplication(app, forcedOutcome = null) {
  const amount = Number(app.form.amount || 0);
  const term = Number(app.form.termMonths || 12);
  const inflow = Number(app.form.averageMonthlyInflow || 0);
  const outflow = Number(app.form.averageMonthlyOutflow || 0);
  const rate = Number(app.product.terms.annualRatePercent) / 100 / 12;

  const monthlyPayment =
    rate > 0 ? (amount * rate) / (1 - Math.pow(1 + rate, -term)) : amount / term;
  const netCashflow = inflow - outflow;

  if (forcedOutcome === 'TECHNICAL_HOLD') {
    app.status = 'TECHNICAL_HOLD';
    app.processingError = {
      code: 'SCORING_TIMEOUT',
      message: 'Проверка не завершена. Данные сохранены, можно повторить.',
      occurredAt: new Date().toISOString(),
    };
    app.decision = null;
    app.requiredInformation = [];
  } else if (
    forcedOutcome === 'NEEDS_INFO' ||
    (!forcedOutcome && inflow === 0 && !app._zeroConfirmed)
  ) {
    app.status = 'NEEDS_INFO';
    app.requiredInformation = [
      {
        clarificationId: '1f9bd36f-e62b-47cd-a8c3-fc3227fc6893',
        field: 'form.averageMonthlyInflow',
        code: 'ZERO_INFLOW_CONFIRMATION',
        message:
          'Подтвердите, что среднемесячные поступления за указанный отчётный период равны нулю, либо исправьте значение.',
        observedValue: '0.00',
      },
    ];
    app.decision = null;
    app.processingError = null;
  } else if (
    forcedOutcome === 'DECLINED' ||
    (!forcedOutcome && netCashflow < monthlyPayment * 1.15)
  ) {
    app.status = 'DECLINED';
    app.decision = {
      type: 'DECLINED',
      decidedAt: new Date().toISOString(),
      reasons: [
        {
          code: 'RISK_THRESHOLD_EXCEEDED',
          message:
            'Оценка риска не соответствует условиям демонстрационной кредитной политики (свободный денежный поток ниже расчётной кредитной нагрузки).',
        },
      ],
    };
    app.requiredInformation = [];
    app.processingError = null;
  } else {
    app.status = 'APPROVED';
    app.decision = {
      type: 'APPROVED',
      decidedAt: new Date().toISOString(),
      reasons: [],
      terms: {
        amount: app.form.amount,
        currency: 'RUB',
        termMonths: app.form.termMonths,
        annualRatePercent: app.product.terms.annualRatePercent,
        repaymentDescription: app.product.terms.repaymentDescription,
      },
    };
    app.requiredInformation = [];
    app.processingError = null;
  }

  app.version += 1;
  app.updatedAt = new Date().toISOString();
  app.allowedActions = computeAllowedActions(app.status);
  return app;
}

async function realFetch(path, options = {}) {
  const headers = {
    Accept: 'application/json',
    ...(options.body ? { 'Content-Type': 'application/json' } : {}),
    ...(options.headers || {}),
  };

  if (options.method && options.method !== 'GET') {
    headers[csrfHeaderName] = csrfToken;
  }

  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers,
    credentials: 'include',
  });

  if (response.status === 401) sessionPromise = null;
  if (!response.ok) {
    let problem = null;
    try {
      problem = await response.json();
    } catch {
      problem = {
        title: `Ошибка сервера (${response.status})`,
        status: response.status,
        code: 'HTTP_ERROR',
      };
    }
    throw problem;
  }

  return response.json();
}

async function realCommand(path, body, explicitKey) {
  const signature = JSON.stringify(body ?? null);
  let pending = pendingCommands.get(path);
  if (!pending || pending.signature !== signature || explicitKey && explicitKey !== pending.key) {
    pending = { signature, key: explicitKey || generateUUID() };
    pendingCommands.set(path, pending);
  }
  try {
    const result = await realFetch(path, {
      method: 'POST', headers: { 'Idempotency-Key': pending.key },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (pendingCommands.get(path) === pending) pendingCommands.delete(path);
    return result;
  } catch (error) {
    if (error.status >= 400 && error.status < 500 && error.status !== 409 && pendingCommands.get(path) === pending) pendingCommands.delete(path);
    throw error;
  }
}

export function initSessionAndGetUser() {
  if (apiMode === 'mock') return Promise.resolve(MOCK_USER);
  if (!sessionPromise) {
    sessionPromise = (async () => {
      const first = await realFetch('/csrf', { method: 'GET' });
      csrfToken = first.token;
      csrfHeaderName = first.headerName || 'X-CSRF-TOKEN';
      await realFetch('/demo/session', { method: 'POST' });
      const next = await realFetch('/csrf', { method: 'GET' });
      csrfToken = next.token;
      csrfHeaderName = next.headerName || 'X-CSRF-TOKEN';
      return realFetch('/me', { method: 'GET' });
    })().catch(error => { sessionPromise = null; throw error; });
  }
  return sessionPromise;
}

export async function fetchProducts() {
  if (apiMode === 'mock') {
    return { items: MOCK_PRODUCTS };
  }
  return realFetch('/products', { method: 'GET' });
}

export async function fetchApplications() {
  if (apiMode === 'mock') {
    const apps = loadMockApps();
    const summaries = apps.map((a) => ({
      id: a.id,
      productCode: a.product.code,
      productName: a.product.name,
      status: a.status,
      amount: a.form.amount,
      termMonths: a.form.termMonths,
      version: a.version,
      updatedAt: a.updatedAt,
    }));
    return {
      items: summaries,
      page: 0,
      size: 20,
      totalElements: summaries.length,
      totalPages: 1,
    };
  }
  return realFetch('/applications?page=0&size=20', { method: 'GET' });
}

export async function createApplication(productCode, key) {
  if (apiMode === 'mock') {
    const product =
      MOCK_PRODUCTS.find((p) => p.code === productCode) || MOCK_PRODUCTS[0];
    const now = new Date().toISOString();
    const date = new Date();
    const months = product.terms.financialPeriodMonths;
    const start = new Date(Date.UTC(date.getFullYear(), date.getMonth() - months, 1));
    const end = new Date(Date.UTC(date.getFullYear(), date.getMonth(), 0));
    const newApp = {
      id: generateUUID(),
      version: 0,
      status: 'DRAFT',
      product,
      reportingPeriod: {
        startDate: start.toISOString().slice(0, 10),
        endDate: end.toISOString().slice(0, 10),
        months,
      },
      form: {
        amount: product.terms.minAmount,
        termMonths: product.terms.minTermMonths,
        averageMonthlyInflow: '1200000.00',
        averageMonthlyOutflow: '800000.00',
        financingPurpose: null,
        projectBudget: null,
        trancheTermMonths: null,
        governmentContract: null,
        cashBalance: null,
      },
      assessmentId: null,
      lastSubmittedAt: null,
      calculationDate: now.slice(0, 10),
      decision: null,
      requiredInformation: [],
      processingError: null,
      allowedActions: ['UPDATE_FORM', 'SUBMIT'],
      createdAt: now,
      updatedAt: now,
    };
    const apps = loadMockApps();
    apps.unshift(newApp);
    saveMockApps(apps);
    return newApp;
  }

  return realCommand('/applications', { productCode }, key);
}

export async function fetchApplicationById(applicationId) {
  if (apiMode === 'mock') {
    const apps = loadMockApps();
    const app = apps.find((a) => a.id === applicationId);
    if (!app) {
      throw { status: 404, code: 'NOT_FOUND', title: 'Заявка не найдена' };
    }
    if (
      app.status === 'CHECKING' &&
      app._checkingFinishTime &&
      Date.now() >= app._checkingFinishTime
    ) {
      delete app._checkingFinishTime;
      const forced = app._forcedOutcome || null;
      delete app._forcedOutcome;
      evaluateMockApplication(app, forced);
      saveMockApps(apps);
    }
    return { ...app };
  }
  return realFetch(`/applications/${applicationId}`, { method: 'GET' });
}

export async function saveApplicationForm(applicationId, expectedVersion, formPayload) {
  if (apiMode === 'mock') {
    const apps = loadMockApps();
    const app = apps.find((a) => a.id === applicationId);
    if (!app) throw { status: 404, code: 'NOT_FOUND', title: 'Заявка не найдена' };
    if (app.version !== expectedVersion) {
      throw {
        status: 409,
        code: 'VERSION_CONFLICT',
        title: 'Заявка уже изменена',
        currentVersion: app.version,
      };
    }
    app.form = structuredClone(formPayload);
    app.version += 1;
    app.updatedAt = new Date().toISOString();
    saveMockApps(apps);
    return { ...app };
  }

  return realFetch(`/applications/${applicationId}/form`, {
    method: 'PUT',
    body: JSON.stringify({
      expectedVersion,
      form: formPayload,
    }),
  });
}

export async function submitApplication(
  applicationId,
  expectedVersion,
  expectedProductTermsVersion,
  clarificationResponses = [],
  mockForcedOutcome = null,
  key
) {
  if (apiMode === 'mock') {
    const apps = loadMockApps();
    const app = apps.find((a) => a.id === applicationId);
    if (!app) throw { status: 404, code: 'NOT_FOUND', title: 'Заявка не найдена' };

    const errors = [];
    const amountNum = Number(app.form.amount || 0);
    const minAmount = Number(app.product.terms.minAmount);
    const maxAmount = Number(app.product.terms.maxAmount);
    if (!app.form.amount || amountNum < minAmount || amountNum > maxAmount) {
      errors.push({
        field: 'form.amount',
        code: 'OUT_OF_RANGE',
        message: `Укажите сумму от ${formatRubles(minAmount)} до ${formatRubles(maxAmount)}`,
      });
    }
    const termNum = Number(app.form.termMonths || 0);
    if (
      !app.form.termMonths ||
      termNum < app.product.terms.minTermMonths ||
      termNum > app.product.terms.maxTermMonths
    ) {
      errors.push({
        field: 'form.termMonths',
        code: 'OUT_OF_RANGE',
        message: `Срок должен быть от ${app.product.terms.minTermMonths} до ${app.product.terms.maxTermMonths} мес.`,
      });
    }
    if (app.form.averageMonthlyInflow === null) {
      errors.push({
        field: 'form.averageMonthlyInflow',
        code: 'REQUIRED',
        message: 'Укажите среднемесячные поступления (допускается 0.00)',
      });
    }
    if (app.form.averageMonthlyOutflow === null) {
      errors.push({
        field: 'form.averageMonthlyOutflow',
        code: 'REQUIRED',
        message: 'Укажите среднемесячные расходы',
      });
    }
    if (app.form.cashBalance === null) {
      errors.push({ field: 'form.cashBalance', code: 'REQUIRED', message: 'Укажите свободные собственные деньги' });
    }
    if (app.product.code === 'LONG_TERM') {
      if (!app.form.financingPurpose) errors.push({ field: 'form.financingPurpose', code: 'REQUIRED', message: 'Укажите цель финансирования' });
      if (!(Number(app.form.projectBudget) > 0)) errors.push({ field: 'form.projectBudget', code: 'POSITIVE_REQUIRED', message: 'Укажите положительный бюджет проекта' });
    }
    if (app.product.code === 'REVOLVING_LINE' && !(app.form.trancheTermMonths >= 3 && app.form.trancheTermMonths <= 6)) {
      errors.push({ field: 'form.trancheTermMonths', code: 'OUT_OF_RANGE', message: 'Укажите срок транша от 3 до 6 месяцев' });
    }
    if (app.product.code === 'GOVERNMENT_CONTRACT_LINE') {
      for (const field of ['number', 'contractAmount', 'advanceAmount', 'executionEndDate', 'finalPaymentDate']) {
        if (app.form.governmentContract?.[field] == null || app.form.governmentContract[field] === '') errors.push({ field: `form.governmentContract.${field}`, code: 'REQUIRED', message: 'Заполните сведения о контракте' });
      }
    }

    if (errors.length > 0) {
      throw {
        status: 422,
        code: 'VALIDATION_FAILED',
        title: 'Проверьте правильность заполнения полей',
        errors,
      };
    }

    if (app.status === 'NEEDS_INFO') {
      const resp = clarificationResponses[0];
      if (!resp) {
        throw {
          status: 422,
          code: 'CLARIFICATION_RESPONSE_REQUIRED',
          title: 'Выберите вариант ответа на запрос уточнения',
        };
      }
      if (resp.response === 'CONFIRM_VALUE' && Number(app.form.averageMonthlyInflow) !== 0) {
        throw {
          status: 422,
          code: 'CLARIFICATION_RESPONSE_CONFLICT',
          title: 'Подтверждение нуля допустимо только при сохранённом значении 0.00',
        };
      }
      if (resp.response === 'VALUE_CORRECTED' && Number(app.form.averageMonthlyInflow) <= 0) {
        throw {
          status: 422,
          code: 'CLARIFICATION_RESPONSE_CONFLICT',
          title: 'Для ответа «Значение исправлено» укажите поступления больше 0.00',
        };
      }
      app._zeroConfirmed = true;
    }

    app.status = 'CHECKING';
    app.version += 1;
    app.assessmentId = generateUUID();
    app.lastSubmittedAt = new Date().toISOString();
    app.updatedAt = app.lastSubmittedAt;
    app.allowedActions = [];
    app._checkingFinishTime = Date.now() + 2200;
    app._forcedOutcome = mockForcedOutcome;
    saveMockApps(apps);

    return {
      applicationId: app.id,
      assessmentId: app.assessmentId,
      status: 'CHECKING',
      version: app.version,
      acceptedAt: app.lastSubmittedAt,
      statusUrl: `/api/v1/applications/${app.id}`,
      pollAfterSeconds: 2,
    };
  }

  return realCommand(`/applications/${applicationId}/submit`, {
    expectedVersion, expectedProductTermsVersion, clarificationResponses,
  }, key);
}

export async function retryApplication(applicationId, expectedVersion, key) {
  if (apiMode === 'mock') {
    const apps = loadMockApps();
    const app = apps.find((a) => a.id === applicationId);
    if (!app) throw { status: 404, code: 'NOT_FOUND', title: 'Заявка не найдена' };
    app.status = 'CHECKING';
    app.version += 1;
    app.assessmentId = generateUUID();
    app.processingError = null;
    app.allowedActions = [];
    app._checkingFinishTime = Date.now() + 2000;
    saveMockApps(apps);

    return {
      applicationId: app.id,
      assessmentId: app.assessmentId,
      status: 'CHECKING',
      version: app.version,
      acceptedAt: new Date().toISOString(),
      statusUrl: `/api/v1/applications/${app.id}`,
      pollAfterSeconds: 2,
    };
  }

  return realCommand(`/applications/${applicationId}/retry`, { expectedVersion }, key);
}

export async function initAssistantConversation(key) {
  if (apiMode === 'mock') {
    const existingId = localStorage.getItem('alfa_mock_conv_id_v2') || generateUUID();
    localStorage.setItem('alfa_mock_conv_id_v2', existingId);
    return {
      id: existingId,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
  }
  await initSessionAndGetUser();
  return realCommand('/assistant/conversations', undefined, key);
}

export async function fetchAssistantMessages(conversationId, afterSequence = 0) {
  if (apiMode === 'mock') {
    const raw = localStorage.getItem(`alfa_mock_msgs_v2_${conversationId}`);
    const items = raw ? JSON.parse(raw) : [];
    return {
      items,
      lastSequence: items.length,
      hasMore: false,
    };
  }
  return realFetch(
    `/assistant/conversations/${conversationId}/messages?afterSequence=${afterSequence}&limit=100`,
    { method: 'GET' }
  );
}

export async function sendAssistantMessage(conversationId, text, context = {}, key) {
  if (apiMode === 'mock') {
    const raw = localStorage.getItem(`alfa_mock_msgs_v2_${conversationId}`);
    const items = raw ? JSON.parse(raw) : [];
    const q = text.toLowerCase();

    let replyText =
      'В нашем каталоге 5 продуктов: Оборотный кредит (24%), Овердрафт (26%), Возобновляемая кредитная линия (23%), Кредит на длительный срок (22%) и Кредитная линия на госконтракт (21%). Расскажите, на какую задачу нужны деньги?';
    let recommendedProductCode = null;

    if (
      q.includes('печь') ||
      q.includes('станок') ||
      q.includes('оборудован') ||
      q.includes('ремонт') ||
      q.includes('расширен') ||
      q.includes('длительн')
    ) {
      replyText =
        'Для покупки оборудования, ремонта или расширения бизнеса подходит «Кредит на длительный срок» (от 500 тыс. до 5 млн ₽, срок от 24 до 60 мес., ставка 22% годовых). Вы получаете всю сумму сразу и возвращаете равными платежами.';
      recommendedProductCode = 'LONG_TERM';
    } else if (q.includes('овердрафт') || q.includes('позже') || q.includes('недел') || q.includes('30 дн')) {
      replyText =
        'Если нужно провести платёж сейчас, а покупатель перечислит деньги позже, выберите «Овердрафт» (лимит от 50 тыс. до 1 млн ₽, ставка 26% годовых только на использованный остаток, погашение за 30 дней).';
      recommendedProductCode = 'OVERDRAFT';
    } else if (q.includes('частям') || q.includes('регулярн') || q.includes('возобнов')) {
      replyText =
        'Если деньги нужны регулярно частями, подойдёт «Возобновляемая кредитная линия» (лимит до 5 млн ₽ на 24 мес., ставка 23% годовых). Погашенная часть основного долга снова становится доступной.';
      recommendedProductCode = 'REVOLVING_LINE';
    } else if (q.includes('госконтракт') || q.includes('44-фз') || q.includes('заказчик') || q.includes('тендер')) {
      replyText =
        'Для исполнения уже заключённого контракта по 44-ФЗ выберите «Кредитную линию на госконтракт» (лимит до 10 млн ₽, до 70% суммы контракта за вычетом аванса, ставка 21% годовых).';
      recommendedProductCode = 'GOVERNMENT_CONTRACT_LINE';
    } else if (q.includes('закуп') || q.includes('оборот') || q.includes('товар') || q.includes('сырь')) {
      replyText =
        'Для разовой закупки товаров, сырья или оплаты аренды оптимален «Оборотный кредит» (от 100 тыс. до 3 млн ₽ на срок от 3 до 12 мес., ставка 24% годовых).';
      recommendedProductCode = 'WORKING_CAPITAL';
    } else if (q.includes('доход') || q.includes('поступлен') || context.fieldCode === 'form.averageMonthlyInflow') {
      replyText =
        'В поле «Среднемесячные поступления» укажите среднюю выручку компании за месяц. Если поступлений не было, укажите 0.00 — система попросит подтвердить это на следующем шаге.';
    } else if (q.includes('расход') || q.includes('платеж') || context.fieldCode === 'form.averageMonthlyOutflow') {
      replyText =
        'В поле «Среднемесячные расходы» укажите регулярные ежемесячные затраты бизнеса: закупка сырья, аренда, зарплата, налоги и действующие обязательства.';
    }

    const now = new Date().toISOString();
    const userMessage = {
      id: generateUUID(),
      sequence: items.length + 1,
      role: 'USER',
      text,
      recommendedProductCode: null,
      replyToMessageId: null,
      createdAt: now,
    };
    const assistantMessage = {
      id: generateUUID(),
      sequence: items.length + 2,
      role: 'ASSISTANT',
      text: replyText,
      recommendedProductCode,
      replyToMessageId: userMessage.id,
      createdAt: now,
    };

    items.push(userMessage, assistantMessage);
    localStorage.setItem(`alfa_mock_msgs_v2_${conversationId}`, JSON.stringify(items));

    return {
      conversationId,
      userMessage,
      assistantMessage,
    };
  }

  return realCommand(`/assistant/conversations/${conversationId}/messages`, {
    text, context: { applicationId: context.applicationId || null, productCode: context.productCode || null, fieldCode: context.fieldCode || null },
  }, key);
}

export function forceMockStatus(applicationId, status) {
  const apps = loadMockApps();
  const app = apps.find((a) => a.id === applicationId);
  if (!app) return null;

  if (status === 'DRAFT') {
    app.status = 'DRAFT';
    app.decision = null;
    app.requiredInformation = [];
    app.processingError = null;
    app.version += 1;
    app.allowedActions = ['UPDATE_FORM', 'SUBMIT'];
  } else {
    evaluateMockApplication(app, status);
  }
  saveMockApps(apps);
  return { ...app };
}
