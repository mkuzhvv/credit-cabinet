import { useState, useEffect, useRef, useCallback } from 'react';
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
  const initial = application.form;
  const [amount, setAmount] = useState(initial.amount ?? application.product.terms.minAmount);
  const [termMonths, setTermMonths] = useState(initial.termMonths ?? application.product.terms.minTermMonths);
  const [inflow, setInflow] = useState(initial.averageMonthlyInflow ?? '');
  const [outflow, setOutflow] = useState(initial.averageMonthlyOutflow ?? '');
  const [cashBalance, setCashBalance] = useState(initial.cashBalance ?? '');
  const [financingPurpose, setFinancingPurpose] = useState(initial.financingPurpose ?? '');
  const [projectBudget, setProjectBudget] = useState(initial.projectBudget ?? '');
  const [trancheTermMonths, setTrancheTermMonths] = useState(initial.trancheTermMonths ?? '');
  const [governmentContract, setGovernmentContract] = useState(initial.governmentContract ?? {});
  const [clarificationMode, setClarificationMode] = useState('CONFIRM_VALUE');
  const [clarificationComment, setClarificationComment] = useState('За указанный отчётный период поступлений от деятельности не было.');
  const [saveState, setSaveState] = useState('saved');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState({});
  const [globalError, setGlobalError] = useState(null);
  const latest = useRef(application);
  const saveQueue = useRef(Promise.resolve());
  const editRevision = useRef(0);
  const submitting = useRef(false);
  latest.current = application;

  const loadForm = useCallback(next => {
    const form = next.form;
    setAmount(form.amount ?? next.product.terms.minAmount);
    setTermMonths(form.termMonths ?? next.product.terms.minTermMonths);
    setInflow(form.averageMonthlyInflow ?? '');
    setOutflow(form.averageMonthlyOutflow ?? '');
    setCashBalance(form.cashBalance ?? '');
    setFinancingPurpose(form.financingPurpose ?? '');
    setProjectBudget(form.projectBudget ?? '');
    setTrancheTermMonths(form.trancheTermMonths ?? '');
    setGovernmentContract(form.governmentContract ?? {});
    setFieldErrors({});
    setGlobalError(null);
    setSaveState('saved');
  }, []);

  const applicationId = application.id;
  const status = application.status;
  useEffect(() => { loadForm(latest.current); }, [applicationId, status, loadForm]);

  useEffect(() => {
    if (status !== 'CHECKING') return;
    let active = true;
    let timer;
    const poll = async () => {
      try {
        const updated = await fetchApplicationById(applicationId);
        if (active) setApplication(updated);
      } catch (error) {
        if (active && error.status === 401) setGlobalError('Сессия завершилась. Обновите страницу и войдите снова.');
      }
      if (active) timer = setTimeout(poll, 2000);
    };
    timer = setTimeout(poll, 2000);
    return () => { active = false; clearTimeout(timer); };
  }, [applicationId, status, setApplication]);

  const { product, reportingPeriod, decision, requiredInformation, processingError } = application;
  const { terms } = product;
  const isEditable = status === 'DRAFT' || status === 'NEEDS_INFO';
  const isNeedsInfo = status === 'NEEDS_INFO';
  const isAnnuity = terms.repaymentType === 'ANNUITY';
  const markDirty = () => { editRevision.current++; setSaveState('dirty'); };
  const buildFormPayload = () => ({
    amount: toMoneyString(amount),
    termMonths: termMonths === '' ? null : Number(termMonths),
    averageMonthlyInflow: toMoneyString(inflow),
    averageMonthlyOutflow: toMoneyString(outflow),
    financingPurpose: product.code === 'LONG_TERM' ? financingPurpose.trim() || null : null,
    projectBudget: product.code === 'LONG_TERM' ? toMoneyString(projectBudget) : null,
    trancheTermMonths: product.code === 'REVOLVING_LINE' && trancheTermMonths !== '' ? Number(trancheTermMonths) : null,
    governmentContract: product.code === 'GOVERNMENT_CONTRACT_LINE' ? {
      lawCode: '44_FZ', number: governmentContract.number?.trim() || null,
      contractAmount: toMoneyString(governmentContract.contractAmount),
      advanceAmount: toMoneyString(governmentContract.advanceAmount),
      executionEndDate: governmentContract.executionEndDate || null,
      finalPaymentDate: governmentContract.finalPaymentDate || null,
    } : null,
    cashBalance: toMoneyString(cashBalance),
  });

  const showError = error => {
    setFieldErrors(Object.fromEntries((error.errors || []).map(item => [item.field, item.message])));
    setGlobalError(error.title || 'Не удалось выполнить запрос. Проверьте соединение и повторите.');
  };

  const handleSaveForm = async (silent = false) => {
    if (!isEditable) return latest.current;
    const body = buildFormPayload();
    const revision = editRevision.current;
    const operation = saveQueue.current.then(async () => {
      const current = latest.current;
      if (JSON.stringify(body) === JSON.stringify(current.form)) { setSaveState(editRevision.current === revision ? 'saved' : 'dirty'); return current; }
      setSaveState('saving');
      if (!silent) { setGlobalError(null); setFieldErrors({}); }
      const updated = await saveApplicationForm(current.id, current.version, body);
      latest.current = updated;
      setApplication(updated);
      setSaveState(editRevision.current === revision ? 'saved' : 'dirty');
      return updated;
    });
    saveQueue.current = operation;
    try {
      return await operation;
    } catch (error) {
      setSaveState('error');
      if (error.code === 'VERSION_CONFLICT') {
        try {
          const fresh = await fetchApplicationById(applicationId);
          latest.current = fresh;
          setApplication(fresh);
          loadForm(fresh);
          setGlobalError('Заявка изменена в другой вкладке. Загружены актуальные данные; внесите свои изменения повторно.');
        } catch (reloadError) { showError(reloadError); }
      } else showError(error);
      throw error;
    } finally {
      if (saveQueue.current === operation) saveQueue.current = Promise.resolve();
    }
  };

  const handleSubmit = async (mockForcedOutcome = null) => {
    if (submitting.current) return;
    submitting.current = true;
    setIsSubmitting(true); setGlobalError(null); setFieldErrors({});
    try {
      const saved = await handleSaveForm(true);
      const responses = saved.status === 'NEEDS_INFO' ? (saved.requiredInformation || []).map(question => ({
        clarificationId: question.clarificationId, response: clarificationMode,
        comment: clarificationMode === 'CONFIRM_VALUE' ? clarificationComment.trim() || null : null,
      })) : [];
      const accepted = await submitApplication(saved.id, saved.version, saved.product.terms.version, responses, mockForcedOutcome);
      setApplication({ ...saved, status: accepted.status, version: accepted.version, assessmentId: accepted.assessmentId, allowedActions: [] });
    } catch (error) { if (error.code !== 'VERSION_CONFLICT') showError(error); }
    finally { submitting.current = false; setIsSubmitting(false); }
  };

  const handleRetry = async () => {
    if (submitting.current) return;
    submitting.current = true; setIsSubmitting(true); setGlobalError(null);
    try {
      const accepted = await retryApplication(application.id, application.version);
      setApplication({ ...application, status: accepted.status, version: accepted.version, assessmentId: accepted.assessmentId, processingError: null, allowedActions: [] });
    } catch (error) { showError(error); }
    finally { submitting.current = false; setIsSubmitting(false); }
  };

  const updateContract = (name, value) => { setGovernmentContract(previous => ({ ...previous, [name]: value })); markDirty(); };
  const saveOnBlur = () => { handleSaveForm(true).catch(() => {}); };
  const fieldProps = { disabled: !isEditable || isNeedsInfo, onBlur: saveOnBlur, onFocus: onFocusField, errors: fieldErrors };

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
              onClick={() => handleSaveForm(false).catch(() => {})}
              disabled={saveState === 'saving' || isSubmitting}
              className="py-2 px-3.5 rounded-xl border border-gray-300 hover:bg-gray-50 text-xs font-semibold flex items-center gap-1.5 cursor-pointer"
            >
              <Save className="w-3.5 h-3.5" />
              <span>Сохранить</span>
            </button>
          </div>
        )}
      </div>


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


        <div className="space-y-2">
          <div className="flex justify-between items-baseline">
            <label className="text-sm font-semibold text-gray-800">
              {terms.financingType === 'TERM_LOAN' ? 'Сумма кредита (₽)' : 'Запрашиваемый лимит (₽)'}
            </label>
            <span className="text-xs text-gray-500">
              от {formatRubles(terms.minAmount)} до {formatRubles(terms.maxAmount)}
            </span>
          </div>
          <input
            type="number"
            disabled={!isEditable || isNeedsInfo}
            aria-label="Сумма или лимит"
            step="0.01"
            value={amount}
            onFocus={() => onFocusField?.('form.amount')}
            onChange={(e) => {
              setAmount(e.target.value);
              markDirty();
            }}
            onBlur={saveOnBlur}
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
              markDirty();
            }}
            onMouseUp={saveOnBlur}
            className="w-full cursor-pointer"
          />
          {fieldErrors['form.amount'] && (
            <p className="text-xs text-[#EF3124] font-medium">
              {fieldErrors['form.amount']}
            </p>
          )}
        </div>


        <div className="space-y-2">
          <div className="flex justify-between items-baseline">
            <label className="text-sm font-semibold text-gray-800">
              {terms.financingType === 'TERM_LOAN' ? 'Срок кредита (в месяцах)' : 'Срок действия лимита (в месяцах)'}
            </label>
            <span className="text-xs text-gray-500">
              от {terms.minTermMonths} до {terms.maxTermMonths} мес.
            </span>
          </div>
          <input
            type="number"
            disabled={!isEditable || isNeedsInfo}
            aria-label="Срок кредита или лимита"
            value={termMonths}
            onFocus={() => onFocusField?.('form.termMonths')}
            onChange={(e) => {
              setTermMonths(e.target.value);
              markDirty();
            }}
            onBlur={saveOnBlur}
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
              markDirty();
            }}
            onMouseUp={saveOnBlur}
            className="w-full cursor-pointer"
          />
          {fieldErrors['form.termMonths'] && (
            <p className="text-xs text-[#EF3124] font-medium">
              {fieldErrors['form.termMonths']}
            </p>
          )}
        </div>


        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2">
          <div className="space-y-1.5">
            <label className="text-sm font-semibold text-gray-800">
              Среднемесячные поступления (₽)
            </label>
            <input
              type="number"
              disabled={!isEditable}
              aria-label="Среднемесячные поступления"
              step="0.01"
              value={inflow}
              onFocus={() => onFocusField?.('form.averageMonthlyInflow')}
              onChange={(e) => {
                setInflow(e.target.value);
                markDirty();
              }}
              onBlur={saveOnBlur}
              placeholder="Например, 1200000"
              className={`w-full px-4 py-3 rounded-xl border font-semibold text-sm focus:outline-none ${
                isNeedsInfo
                  ? 'border-amber-500 bg-amber-50/30 ring-2 ring-amber-400/30'
                  : 'border-gray-300 focus:border-[#EF3124]'
              } disabled:bg-gray-100`}
            />
            <p className="text-[11px] text-gray-500">
              Поступления от деятельности за указанный период, делённые на число месяцев. Не включайте новые кредиты, вложения собственника и внутренние переводы.
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
              aria-label="Среднемесячные расходы"
              step="0.01"
              value={outflow}
              onFocus={() => onFocusField?.('form.averageMonthlyOutflow')}
              onChange={(e) => {
                setOutflow(e.target.value);
                markDirty();
              }}
              onBlur={saveOnBlur}
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


        <ApplicationField field="form.cashBalance" label="Свободные собственные деньги (₽)" type="number"
          value={cashBalance} onChange={value => { setCashBalance(value); markDirty(); }} {...fieldProps} />
        <p className="text-[11px] text-gray-500">Доступные собственные средства до нового кредита. Неизвестное значение оставьте пустым; настоящий ноль укажите как 0.</p>

        {product.code === 'LONG_TERM' && (
          <div className="p-5 rounded-2xl bg-purple-50/50 border border-purple-200/80 space-y-4">
            <div className="text-xs font-bold uppercase tracking-wider text-purple-800">Цель финансирования и бюджет проекта</div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <ApplicationField field="form.financingPurpose" label="Цель финансирования" value={financingPurpose}
                onChange={value => { setFinancingPurpose(value); markDirty(); }} {...fieldProps} />
              <ApplicationField field="form.projectBudget" label="Бюджет проекта (₽)" type="number" value={projectBudget}
                onChange={value => { setProjectBudget(value); markDirty(); }} {...fieldProps} />
            </div>
          </div>
        )}
        {product.code === 'REVOLVING_LINE' && (
          <div className="p-5 rounded-2xl bg-emerald-50/50 border border-emerald-200/80 space-y-4">
            <ApplicationField field="form.trancheTermMonths" label="Срок отдельного транша (месяцы)" type="number" value={trancheTermMonths}
              min={terms.revolvingLineRules?.minTrancheTermMonths} max={terms.revolvingLineRules?.maxTrancheTermMonths} step="1"
              onChange={value => { setTrancheTermMonths(value); markDirty(); }} {...fieldProps} />
            <p className="text-[11px] text-gray-500">Срок транша {terms.revolvingLineRules?.minTrancheTermMonths}–{terms.revolvingLineRules?.maxTrancheTermMonths} месяцев и не позднее конца линии. Срок линии указан выше отдельно.</p>
          </div>
        )}
        {product.code === 'GOVERNMENT_CONTRACT_LINE' && (
          <div className="p-5 rounded-2xl bg-indigo-50/50 border border-indigo-200/80 space-y-4">
            <div className="text-xs font-bold uppercase tracking-wider text-indigo-800">Заключённый государственный контракт по 44-ФЗ</div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              {[
                ['number', 'Номер контракта', 'text'],
                ['contractAmount', 'Стоимость контракта (₽)', 'number'],
                ['advanceAmount', 'Аванс (₽)', 'number'],
                ['executionEndDate', 'Дата завершения исполнения', 'date'],
                ['finalPaymentDate', 'Дата окончательной оплаты', 'date'],
              ].map(([name, label, type]) => <ApplicationField key={name} field={`form.governmentContract.${name}`} label={label} type={type}
                value={governmentContract[name] ?? ''} onChange={value => updateContract(name, value)} {...fieldProps} />)}
            </div>
            <p className="text-[11px] text-gray-500">Лимит до {terms.governmentContractRules?.maxNetContractSharePercent}% от стоимости за вычетом аванса. Возврат долга не восстанавливает лимит.</p>
          </div>
        )}


        <div className="bg-gray-50 p-4 rounded-2xl border border-gray-200/80 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
          <div className="flex items-center gap-2.5">
            <TrendingUp className="w-5 h-5 text-[#EF3124]" />
            <div className="text-xs">
              <div className="text-gray-500">
                {isAnnuity ? 'Ориентировочный платёж по графику:' : 'Погашение по условиям продукта:'}
              </div>
              <div className="text-base font-bold text-gray-900">
                {isAnnuity ? `~ ${formatRubles(estMonthlyPayment)} / мес.` : terms.repaymentDescription}
              </div>
            </div>
          </div>

          <div className="text-xs">
            <div className="text-gray-500">
              Чистый денежный поток (доходы − расходы):
            </div>
            <div
              className={`text-base font-bold ${
                netCashflow >= (isAnnuity ? estMonthlyPayment : 0)
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
function ApplicationField({ field, label, type = 'text', value, onChange, onBlur, onFocus, errors, disabled, min, max, step = '0.01' }) {
  return <div className="space-y-1.5">
    <label htmlFor={field} className="text-sm font-semibold text-gray-800">{label}</label>
    <input id={field} type={type} step={type === 'number' ? step : undefined} min={min} max={max} disabled={disabled} value={value}
      onChange={event => onChange(event.target.value)} onBlur={onBlur} onFocus={() => onFocus?.(field)}
      className="w-full px-4 py-3 rounded-xl border border-gray-300 font-semibold text-sm focus:outline-none focus:border-[#EF3124] disabled:bg-gray-100" />
    {errors[field] && <p className="text-xs text-[#EF3124] font-medium">{errors[field]}</p>}
  </div>;
}
