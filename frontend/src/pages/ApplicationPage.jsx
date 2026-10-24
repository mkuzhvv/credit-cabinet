import { useState, useEffect } from 'react';
import {
  ArrowLeft,
  Save,
  Send,
  Loader2,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  RefreshCw,
  Calendar,
  TrendingUp,
  Sliders,
} from 'lucide-react';
import {
  saveApplicationForm,
  submitApplication,
  retryApplication,
  fetchApplicationById,
  toMoneyString,
  formatRubles,
  forceMockStatus,
} from '../api/client';

export default function ApplicationPage({
  application,
  setApplication,
  onBackToProducts,
  onCreateNewWithProduct,
  onFocusField,
  apiMode,
}) {
  const [amount, setAmount] = useState('500000');
  const [termMonths, setTermMonths] = useState(12);
  const [inflow, setInflow] = useState('1200000');
  const [outflow, setOutflow] = useState('800000');
  const [eqName, setEqName] = useState('');
  const [eqPrice, setEqPrice] = useState('');

  const [clarificationMode, setClarificationMode] = useState('CONFIRM_VALUE');
  const [clarificationComment, setClarificationComment] = useState(
    'За указанный отчётный период поступлений от деятельности не было.'
  );

  const [saveState, setSaveState] = useState('saved');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState({});
  const [globalError, setGlobalError] = useState(null);

  useEffect(() => {
    if (!application) return;
    const { form, product } = application;
    setAmount(
      form.amount !== null
        ? String(Math.round(Number(form.amount)))
        : String(Math.round(Number(product.terms.minAmount)))
    );
    setTermMonths(form.termMonths ?? product.terms.minTermMonths);
    setInflow(
      form.averageMonthlyInflow !== null
        ? String(Math.round(Number(form.averageMonthlyInflow)))
        : ''
    );
    setOutflow(
      form.averageMonthlyOutflow !== null
        ? String(Math.round(Number(form.averageMonthlyOutflow)))
        : ''
    );
    setEqName(form.equipment?.name || '');
    setEqPrice(
      form.equipment?.purchasePrice
        ? String(Math.round(Number(form.equipment.purchasePrice)))
        : ''
    );
    setFieldErrors({});
    setGlobalError(null);
  }, [application?.id, application?.status]);

  useEffect(() => {
    if (!application || application.status !== 'CHECKING') return;

    const timer = setInterval(async () => {
      try {
        const updated = await fetchApplicationById(application.id);
        setApplication(updated);
      } catch {
        // Игнорируем разовую ошибку сети при опросе
      }
    }, 2000);

    return () => clearInterval(timer);
  }, [application?.id, application?.status]);

  if (!application) return null;

  const {
    product,
    status,
    reportingPeriod,
    decision,
    requiredInformation,
    processingError,
  } = application;
  const { terms } = product;
  const isEditable = status === 'DRAFT' || status === 'NEEDS_INFO';
  const isNeedsInfo = status === 'NEEDS_INFO';

  const buildFormPayload = () => ({
    amount: toMoneyString(amount),
    termMonths: termMonths ? Number(termMonths) : null,
    averageMonthlyInflow: toMoneyString(inflow),
    averageMonthlyOutflow: toMoneyString(outflow),
    equipment:
      product.code === 'EQUIPMENT'
        ? {
            name: eqName.trim() || null,
            purchasePrice: toMoneyString(eqPrice),
          }
        : null,
  });

  const handleSaveForm = async (silent = false) => {
    if (!isEditable) return application;
    setSaveState('saving');
    if (!silent) {
      setGlobalError(null);
      setFieldErrors({});
    }

    try {
      const updated = await saveApplicationForm(
        application.id,
        application.version,
        buildFormPayload()
      );
      setApplication(updated);
      setSaveState('saved');
      return updated;
    } catch (err) {
      setSaveState('error');
      if (err?.code === 'VERSION_CONFLICT') {
        const fresh = await fetchApplicationById(application.id);
        setApplication(fresh);
        setGlobalError(
          'Версия заявки обновилась на сервере. Данные перезагружены.'
        );
      } else if (err?.errors) {
        const map = {};
        err.errors.forEach((e) => {
          map[e.field] = e.message;
        });
        setFieldErrors(map);
      } else {
        setGlobalError(err?.title || 'Не удалось сохранить черновик');
      }
      throw err;
    }
  };

  const handleSubmit = async (mockForcedOutcome = null) => {
    setIsSubmitting(true);
    setGlobalError(null);
    setFieldErrors({});

    try {
      const savedApp = await handleSaveForm(true);

      let clarificationResponses = [];
      if (
        savedApp.status === 'NEEDS_INFO' &&
        savedApp.requiredInformation?.length > 0
      ) {
        clarificationResponses = savedApp.requiredInformation.map((req) => ({
          clarificationId: req.clarificationId,
          response: clarificationMode,
          comment:
            clarificationMode === 'CONFIRM_VALUE'
              ? clarificationComment.trim() || null
              : null,
        }));
      }

      await submitApplication(
        savedApp.id,
        savedApp.version,
        savedApp.product.terms.version,
        clarificationResponses,
        mockForcedOutcome
      );

      const checkingApp = await fetchApplicationById(savedApp.id);
      setApplication(checkingApp);
    } catch (err) {
      if (err?.errors) {
        const map = {};
        err.errors.forEach((e) => {
          map[e.field] = e.message;
        });
        setFieldErrors(map);
      }
      setGlobalError(err?.title || 'Проверьте заполнение полей перед отправкой');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleRetry = async () => {
    setIsSubmitting(true);
    setGlobalError(null);
    try {
      await retryApplication(application.id, application.version);
      const updated = await fetchApplicationById(application.id);
      setApplication(updated);
    } catch (err) {
      setGlobalError(err?.title || 'Не удалось запустить повторную проверку');
    } finally {
      setIsSubmitting(false);
    }
  };

  const numAmount = Number(amount || 0);
  const numTerm = Number(termMonths || 12);
  const monthlyRate = Number(terms.annualRatePercent) / 100 / 12;
  const estMonthlyPayment =
    numAmount > 0 && numTerm > 0
      ? Math.round(
          (numAmount * monthlyRate) / (1 - Math.pow(1 + monthlyRate, -numTerm))
        )
      : 0;
  const netCashflow = Number(inflow || 0) - Number(outflow || 0);

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-20">
      {/* Верхняя панель навигации и сохранения */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white p-5 rounded-3xl border border-gray-200/90 shadow-xs">
        <div className="flex items-center gap-3">
          <button
            onClick={onBackToProducts}
            className="p-2.5 rounded-xl border border-gray-200 hover:bg-gray-50 text-gray-700 transition-all cursor-pointer"
          >
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-lg sm:text-xl font-bold text-gray-900">
                {product.name}
              </h1>
              <span className="text-xs px-2.5 py-0.5 rounded-full bg-gray-100 text-gray-600 font-medium">
                v{application.version}
              </span>
            </div>
            <p className="text-xs text-gray-500">
              Ставка {terms.annualRatePercent}% годовых • Сумма от{' '}
              {formatRubles(terms.minAmount)} до {formatRubles(terms.maxAmount)}
            </p>
          </div>
        </div>

        {isEditable && (
          <div className="flex items-center gap-3">
            <span className="text-xs text-gray-500">
              {saveState === 'saving' && 'Сохраняем...'}
              {saveState === 'saved' && '✓ Черновик сохранён'}
              {saveState === 'dirty' && 'Есть несохранённые изменения'}
              {saveState === 'error' && 'Ошибка сохранения'}
            </span>
            <button
              onClick={() => handleSaveForm(false)}
              className="py-2 px-3.5 rounded-xl border border-gray-300 hover:bg-gray-50 text-xs font-semibold flex items-center gap-1.5 cursor-pointer"
            >
              <Save className="w-3.5 h-3.5" />
              <span>Сохранить</span>
            </button>
          </div>
        )}
      </div>

      {/* ДЕМО-ПУЛЬТ БЫСТРОЙ ПРОВЕРКИ ВСЕХ 6 СТАТУСОВ */}
      {apiMode === 'mock' && (
        <div className="bg-[#121214] text-white p-4 rounded-2xl flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2 text-xs font-semibold text-gray-300">
            <Sliders className="w-4 h-4 text-[#EF3124]" />
            <span>Демо-пульт тестирования состояний OpenAPI:</span>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {[
              { code: 'DRAFT', label: '1. Черновик' },
              { code: 'APPROVED', label: '2. Одобрено' },
              { code: 'DECLINED', label: '3. Отказ' },
              { code: 'NEEDS_INFO', label: '4. Уточнение 0 ₽' },
              { code: 'TECHNICAL_HOLD', label: '5. Тех. сбой' },
            ].map((item) => (
              <button
                key={item.code}
                onClick={() => {
                  const updated = forceMockStatus(application.id, item.code);
                  if (updated) setApplication(updated);
                }}
                className={`px-3 py-1.5 rounded-xl text-xs font-semibold transition-all cursor-pointer ${
                  status === item.code
                    ? 'bg-[#EF3124] text-white'
                    : 'bg-white/10 hover:bg-white/20 text-gray-200'
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 1. Статус CHECKING */}
      {status === 'CHECKING' && (
        <div className="bg-white rounded-3xl p-8 border border-blue-200 shadow-xs text-center space-y-5 animate-fadeIn">
          <div className="w-14 h-14 rounded-2xl bg-blue-50 text-blue-600 flex items-center justify-center mx-auto">
            <Loader2 className="w-7 h-7 animate-spin" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-gray-900">
              Выполняется автоматическая проверка заявки
            </h2>
            <p className="text-sm text-gray-600 mt-1">
              Опрашиваем статус каждые 2 секунды. Пожалуйста, не закрывайте страницу...
            </p>
          </div>
          <div className="max-w-md mx-auto grid grid-cols-3 gap-2 pt-2 text-xs font-medium">
            <div className="p-2.5 rounded-xl bg-emerald-50 text-emerald-700 border border-emerald-200">
              ✓ Данные приняты
            </div>
            <div className="p-2.5 rounded-xl bg-blue-50 text-blue-700 border border-blue-200 animate-pulse">
              ⚙️ Оценка ML-моделью
            </div>
            <div className="p-2.5 rounded-xl bg-gray-100 text-gray-500">
              📋 Правила банка
            </div>
          </div>
        </div>
      )}

      {/* 2. Статус APPROVED */}
      {status === 'APPROVED' && decision && (
        <div className="bg-emerald-50/90 rounded-3xl p-6 sm:p-8 border border-emerald-200 space-y-5 animate-fadeIn">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-2xl bg-emerald-600 text-white flex items-center justify-center shrink-0">
              <CheckCircle2 className="w-6 h-6" />
            </div>
            <div>
              <span className="text-xs font-bold uppercase tracking-wider text-emerald-700">
                Положительное решение
              </span>
              <h2 className="text-2xl font-bold text-gray-900">
                Заявка одобрена
              </h2>
            </div>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 bg-white p-4 rounded-2xl border border-emerald-100">
            <div>
              <div className="text-xs text-gray-500">Одобренная сумма</div>
              <div className="text-lg font-bold text-gray-900">
                {formatRubles(decision.terms?.amount)}
              </div>
            </div>
            <div>
              <div className="text-xs text-gray-500">Срок кредита</div>
              <div className="text-lg font-bold text-gray-900">
                {decision.terms?.termMonths} мес.
              </div>
            </div>
            <div>
              <div className="text-xs text-gray-500">Ставка (демо)</div>
              <div className="text-lg font-bold text-gray-900">
                {decision.terms?.annualRatePercent}% годовых
              </div>
            </div>
          </div>

          <p className="text-xs sm:text-sm text-emerald-900">
            <span className="font-semibold">Порядок погашения:</span>{' '}
            {decision.terms?.repaymentDescription}
          </p>

          <div className="pt-2 flex flex-wrap gap-3">
            <button
              onClick={() => onCreateNewWithProduct(product.code)}
              className="py-2.5 px-5 rounded-xl bg-white border border-emerald-300 text-emerald-900 text-xs sm:text-sm font-semibold hover:bg-emerald-100/50 transition-all cursor-pointer"
            >
              Создать новую заявку
            </button>
          </div>
        </div>
      )}

      {/* 3. Статус DECLINED */}
      {status === 'DECLINED' && decision && (
        <div className="bg-red-50/90 rounded-3xl p-6 sm:p-8 border border-red-200 space-y-5 animate-fadeIn">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-2xl bg-[#EF3124] text-white flex items-center justify-center shrink-0">
              <XCircle className="w-6 h-6" />
            </div>
            <div>
              <span className="text-xs font-bold uppercase tracking-wider text-red-700">
                Решение по заявке
              </span>
              <h2 className="text-2xl font-bold text-gray-900">
                По заявке отказано
              </h2>
            </div>
          </div>

          <div className="bg-white p-4 rounded-2xl border border-red-100 space-y-2">
            <div className="text-xs font-semibold text-gray-500 uppercase">
              Причина решения:
            </div>
            {decision.reasons?.map((r, idx) => (
              <div key={idx} className="text-sm text-gray-800 font-medium">
                • {r.message}{' '}
              </div>
            ))}
          </div>

          <button
            onClick={() => onCreateNewWithProduct(product.code)}
            className="py-2.5 px-5 rounded-xl bg-[#EF3124] hover:bg-[#D92B1E] text-white text-xs sm:text-sm font-semibold transition-all cursor-pointer"
          >
            Оформить новую заявку с другими параметрами
          </button>
        </div>
      )}

      {/* 4. Статус TECHNICAL_HOLD */}
      {status === 'TECHNICAL_HOLD' && (
        <div className="bg-orange-50 rounded-3xl p-6 sm:p-8 border border-orange-200 space-y-4 animate-fadeIn">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-2xl bg-orange-500 text-white flex items-center justify-center shrink-0">
              <AlertTriangle className="w-6 h-6" />
            </div>
            <div>
              <span className="text-xs font-bold uppercase tracking-wider text-orange-700">
                Техническая пауза • Это не отказ по риску
              </span>
              <h2 className="text-xl font-bold text-gray-900">
                Не удалось завершить проверку. Заявка сохранена
              </h2>
            </div>
          </div>

          <p className="text-sm text-gray-700">
            {processingError?.message ||
              'Сервис оценки временно не ответил. Все введённые данные сохранены, вы можете повторить проверку.'}
          </p>

          <button
            onClick={handleRetry}
            disabled={isSubmitting}
            className="py-3 px-5 rounded-xl bg-orange-600 hover:bg-orange-700 text-white text-sm font-semibold flex items-center gap-2 transition-all cursor-pointer disabled:opacity-60"
          >
            <RefreshCw className={`w-4 h-4 ${isSubmitting ? 'animate-spin' : ''}`} />
            <span>Повторить проверку</span>
          </button>
        </div>
      )}

      {/* 5. Статус NEEDS_INFO */}
      {isNeedsInfo && requiredInformation?.length > 0 && (
        <div className="bg-amber-50 rounded-3xl p-6 border border-amber-300 space-y-4 animate-fadeIn">
          <div className="flex items-start gap-3">
            <AlertTriangle className="w-6 h-6 text-amber-600 shrink-0 mt-0.5" />
            <div>
              <span className="text-xs font-bold uppercase tracking-wider text-amber-800">
                Требуется уточнение данных (это не отказ)
              </span>
              <h3 className="text-lg font-bold text-gray-900 mt-0.5">
                {requiredInformation[0].message}
              </h3>
              <p className="text-xs text-amber-800 mt-1">
                Зафиксированное значение в анкете:{' '}
                <span className="font-semibold">
                  {formatRubles(requiredInformation[0].observedValue)}
                </span>
              </p>
            </div>
          </div>

          <div className="bg-white p-4 rounded-2xl border border-amber-200 space-y-3">
            <div className="text-xs font-semibold text-gray-700">
              Выберите действие для продолжения рассмотрения:
            </div>

            <label className="flex items-start gap-2.5 text-sm cursor-pointer">
              <input
                type="radio"
                name="clarificationMode"
                checked={clarificationMode === 'CONFIRM_VALUE'}
                onChange={() => {
                  setClarificationMode('CONFIRM_VALUE');
                  setInflow('0');
                }}
                className="mt-1 accent-[#EF3124]"
              />
              <div>
                <span className="font-semibold text-gray-900">
                  Подтвердить нулевые поступления
                </span>
                <p className="text-xs text-gray-500">
                  Оставить значение 0.00 ₽ и передать заявку дальше на проверку.
                </p>
              </div>
            </label>

            {clarificationMode === 'CONFIRM_VALUE' && (
              <input
                type="text"
                value={clarificationComment}
                onChange={(e) => setClarificationComment(e.target.value)}
                placeholder="Необязательный комментарий..."
                className="w-full text-xs sm:text-sm px-3.5 py-2 rounded-xl border border-gray-300"
              />
            )}

            <label className="flex items-start gap-2.5 text-sm cursor-pointer pt-1">
              <input
                type="radio"
                name="clarificationMode"
                checked={clarificationMode === 'VALUE_CORRECTED'}
                onChange={() => setClarificationMode('VALUE_CORRECTED')}
                className="mt-1 accent-[#EF3124]"
              />
              <div>
                <span className="font-semibold text-gray-900">
                  Я исправил значение поступлений в форме ниже
                </span>
                <p className="text-xs text-gray-500">
                  Укажите положительную сумму в поле «Среднемесячные поступления» ниже и нажмите «Отправить».
                </p>
              </div>
            </label>
          </div>
        </div>
      )}

      {globalError && (
        <div className="p-4 rounded-2xl bg-red-50 border border-red-200 text-red-700 text-xs sm:text-sm font-medium">
          {globalError}
        </div>
      )}

      {/* ФОРМА ЗАЯВКИ */}
      <div className="bg-white rounded-3xl p-6 sm:p-8 border border-gray-200/90 shadow-xs space-y-6">
        <div className="flex items-center justify-between border-b border-gray-100 pb-4">
          <div>
            <h2 className="text-lg font-bold text-gray-900">
              Параметры заявки и финансы компании
            </h2>
            <p className="text-xs text-gray-500 flex items-center gap-1.5 mt-1">
              <Calendar className="w-3.5 h-3.5" />
              <span>
                Отчётный период ({reportingPeriod?.months} мес.): с{' '}
                {reportingPeriod?.startDate} по {reportingPeriod?.endDate}
              </span>
            </p>
          </div>
        </div>

        {/* 1. Сумма кредита */}
        <div className="space-y-2">
          <div className="flex justify-between items-baseline">
            <label className="text-sm font-semibold text-gray-800">
              Сумма кредита (₽)
            </label>
            <span className="text-xs text-gray-500">
              от {formatRubles(terms.minAmount)} до {formatRubles(terms.maxAmount)}
            </span>
          </div>
          <input
            type="number"
            disabled={!isEditable || isNeedsInfo}
            value={amount}
            onFocus={() => onFocusField?.('form.amount')}
            onChange={(e) => {
              setAmount(e.target.value);
              setSaveState('dirty');
            }}
            onBlur={() => handleSaveForm(true)}
            className="w-full px-4 py-3 rounded-xl border border-gray-300 font-semibold text-base focus:outline-none focus:border-[#EF3124] disabled:bg-gray-100"
          />
          <input
            type="range"
            disabled={!isEditable || isNeedsInfo}
            min={Number(terms.minAmount)}
            max={Number(terms.maxAmount)}
            step={50000}
            value={numAmount}
            onChange={(e) => {
              setAmount(e.target.value);
              setSaveState('dirty');
            }}
            onMouseUp={() => handleSaveForm(true)}
            className="w-full cursor-pointer"
          />
          {fieldErrors['form.amount'] && (
            <p className="text-xs text-[#EF3124] font-medium">
              {fieldErrors['form.amount']}
            </p>
          )}
        </div>

        {/* 2. Срок кредита */}
        <div className="space-y-2">
          <div className="flex justify-between items-baseline">
            <label className="text-sm font-semibold text-gray-800">
              Срок кредита (в месяцах)
            </label>
            <span className="text-xs text-gray-500">
              от {terms.minTermMonths} до {terms.maxTermMonths} мес.
            </span>
          </div>
          <input
            type="number"
            disabled={!isEditable || isNeedsInfo}
            value={termMonths}
            onFocus={() => onFocusField?.('form.termMonths')}
            onChange={(e) => {
              setTermMonths(e.target.value);
              setSaveState('dirty');
            }}
            onBlur={() => handleSaveForm(true)}
            className="w-full px-4 py-3 rounded-xl border border-gray-300 font-semibold text-base focus:outline-none focus:border-[#EF3124] disabled:bg-gray-100"
          />
          <input
            type="range"
            disabled={!isEditable || isNeedsInfo}
            min={terms.minTermMonths}
            max={terms.maxTermMonths}
            step={1}
            value={numTerm}
            onChange={(e) => {
              setTermMonths(Number(e.target.value));
              setSaveState('dirty');
            }}
            onMouseUp={() => handleSaveForm(true)}
            className="w-full cursor-pointer"
          />
          {fieldErrors['form.termMonths'] && (
            <p className="text-xs text-[#EF3124] font-medium">
              {fieldErrors['form.termMonths']}
            </p>
          )}
        </div>

        {/* 3. Доходы и расходы */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2">
          <div className="space-y-1.5">
            <label className="text-sm font-semibold text-gray-800">
              Среднемесячные поступления (₽)
            </label>
            <input
              type="number"
              disabled={!isEditable}
              value={inflow}
              onFocus={() => onFocusField?.('form.averageMonthlyInflow')}
              onChange={(e) => {
                setInflow(e.target.value);
                setSaveState('dirty');
              }}
              onBlur={() => handleSaveForm(true)}
              placeholder="Например, 1200000"
              className={`w-full px-4 py-3 rounded-xl border font-semibold text-sm focus:outline-none ${
                isNeedsInfo
                  ? 'border-amber-500 bg-amber-50/30 ring-2 ring-amber-400/30'
                  : 'border-gray-300 focus:border-[#EF3124]'
              } disabled:bg-gray-100`}
            />
            <p className="text-[11px] text-gray-500">
              Укажите 0, чтобы проверить сценарий NEEDS_INFO
            </p>
            {fieldErrors['form.averageMonthlyInflow'] && (
              <p className="text-xs text-[#EF3124] font-medium">
                {fieldErrors['form.averageMonthlyInflow']}
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-semibold text-gray-800">
              Среднемесячные расходы (₽)
            </label>
            <input
              type="number"
              disabled={!isEditable || isNeedsInfo}
              value={outflow}
              onFocus={() => onFocusField?.('form.averageMonthlyOutflow')}
              onChange={(e) => {
                setOutflow(e.target.value);
                setSaveState('dirty');
              }}
              onBlur={() => handleSaveForm(true)}
              placeholder="Например, 800000"
              className="w-full px-4 py-3 rounded-xl border border-gray-300 font-semibold text-sm focus:outline-none focus:border-[#EF3124] disabled:bg-gray-100"
            />
            {fieldErrors['form.averageMonthlyOutflow'] && (
              <p className="text-xs text-[#EF3124] font-medium">
                {fieldErrors['form.averageMonthlyOutflow']}
              </p>
            )}
          </div>
        </div>

        {/* 4. Блок оборудования */}
        {product.code === 'EQUIPMENT' && (
          <div className="p-5 rounded-2xl bg-purple-50/50 border border-purple-200/80 space-y-4">
            <div className="text-xs font-bold uppercase tracking-wider text-purple-800">
              Сведения о приобретаемом оборудовании
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div className="space-y-1">
                <label className="text-xs font-semibold text-gray-700">
                  Наименование оборудования
                </label>
                <input
                  type="text"
                  disabled={!isEditable || isNeedsInfo}
                  value={eqName}
                  onFocus={() => onFocusField?.('form.equipment.name')}
                  onChange={(e) => {
                    setEqName(e.target.value);
                    setSaveState('dirty');
                  }}
                  onBlur={() => handleSaveForm(true)}
                  placeholder="Например: Печь хлебопекарная"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-white border border-gray-300 text-sm focus:outline-none focus:border-[#EF3124] disabled:bg-gray-100"
                />
                {fieldErrors['form.equipment.name'] && (
                  <p className="text-xs text-[#EF3124] font-medium">
                    {fieldErrors['form.equipment.name']}
                  </p>
                )}
              </div>

              <div className="space-y-1">
                <label className="text-xs font-semibold text-gray-700">
                  Стоимость оборудования (₽)
                </label>
                <input
                  type="number"
                  disabled={!isEditable || isNeedsInfo}
                  value={eqPrice}
                  onFocus={() =>
                    onFocusField?.('form.equipment.purchasePrice')
                  }
                  onChange={(e) => {
                    setEqPrice(e.target.value);
                    setSaveState('dirty');
                  }}
                  onBlur={() => handleSaveForm(true)}
                  placeholder="Например: 600000"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-white border border-gray-300 text-sm focus:outline-none focus:border-[#EF3124] disabled:bg-gray-100"
                />
                {fieldErrors['form.equipment.purchasePrice'] && (
                  <p className="text-xs text-[#EF3124] font-medium">
                    {fieldErrors['form.equipment.purchasePrice']}
                  </p>
                )}
              </div>
            </div>
          </div>
        )}

        {/* Финансовая сводка */}
        <div className="bg-gray-50 p-4 rounded-2xl border border-gray-200/80 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
          <div className="flex items-center gap-2.5">
            <TrendingUp className="w-5 h-5 text-[#EF3124]" />
            <div className="text-xs">
              <div className="text-gray-500">
                Ориентировочный платёж по графику:
              </div>
              <div className="text-base font-bold text-gray-900">
                ~ {formatRubles(estMonthlyPayment)} / мес.
              </div>
            </div>
          </div>

          <div className="text-xs">
            <div className="text-gray-500">
              Чистый денежный поток (доходы − расходы):
            </div>
            <div
              className={`text-base font-bold ${
                netCashflow >= estMonthlyPayment
                  ? 'text-emerald-700'
                  : 'text-amber-700'
              }`}
            >
              {formatRubles(netCashflow)} / мес.
            </div>
          </div>
        </div>

        {isEditable && (
          <button
            onClick={() => handleSubmit(null)}
            disabled={isSubmitting}
            className="w-full py-3.5 px-6 rounded-2xl bg-[#EF3124] hover:bg-[#D92B1E] text-white font-bold text-sm sm:text-base shadow-sm transition-all flex items-center justify-center gap-2 cursor-pointer disabled:opacity-60"
          >
            {isSubmitting ? (
              <>
                <Loader2 className="w-5 h-5 animate-spin" />
                <span>Отправляем заявку...</span>
              </>
            ) : (
              <>
                <Send className="w-4 h-4" />
                <span>
                  {isNeedsInfo
                    ? 'Отправить ответ на уточнение и продолжить проверку'
                    : 'Отправить заявку на рассмотрение'}
                </span>
              </>
            )}
          </button>
        )}
      </div>
    </div>
  );
}