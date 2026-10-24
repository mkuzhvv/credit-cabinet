import { useState, useRef, useEffect } from 'react';
import {
  Package,
  Zap,
  Repeat,
  Wrench,
  Landmark,
  Wallet,
  Clock,
  Percent,
  CheckCircle2,
  ArrowRight,
  Sparkles,
  History,
  Loader2,
  Info,
  X,
  AlertCircle,
  ShieldCheck,
} from 'lucide-react';
import { formatRubles } from '../api/client';

import walletImg from '../assets/wallet.jpg';

// Полные данные всех 5 продуктов строго по документу
const PRODUCT_DETAILS = {
  WORKING_CAPITAL: {
    subtitle: 'Одна сумма на закупки и текущие расходы',
    badgeColor: 'bg-blue-50 text-blue-700 border-blue-200',
    icon: Package,
    amountLabel: 'от 100 000 до 3 000 000 ₽',
    termLabel: 'от 3 до 12 месяцев',
    rateLabel: '24% годовых',
    rateNote: 'на остаток долга',
    repayment: 'Ежемесячно равными платежами.',
    requirements:
      'ИП или ООО, бизнес работает от 6 месяцев; нужны сведения о поступлениях, расходах и действующих кредитах.',
    example:
      'Магазину нужно 600 000 ₽ на сезонную закупку товара, который он планирует продать за несколько месяцев.',
    advice:
      'Выбирайте этот продукт, если сумма расходов известна заранее и деньги нужны одной выплатой.',
  },
  OVERDRAFT: {
    subtitle: 'Оплачивайте расходы пока ждёте поступления на счёт',
    badgeColor: 'bg-amber-50 text-amber-800 border-amber-200',
    icon: Zap,
    amountLabel: 'от 50 000 до 1 000 000 ₽',
    termLabel: 'лимит 12 мес. (возврат 30 дн.)',
    rateLabel: '26% годовых',
    rateNote: 'на использованный остаток',
    repayment:
      'Автоматически из поступлений на счёт; начисленные проценты — ежемесячно. Полностью погасить задолженность нужно в течение 30 дней с первого использования.',
    requirements:
      'ИП или ООО, бизнес работает от 6 месяцев; расчётный счёт в нашем банке и регулярные поступления за последние 3 месяца (лимит — не более 30% среднемесячных поступлений).',
    example:
      'Поставщику нужно заплатить 200 000 ₽ сегодня, а покупатель перечислит деньги через неделю.',
    advice:
      'Выбирайте этот продукт, если нехватка денег временная и вы ожидаете поступление, которое позволит закрыть долг в течение 30 дней.',
  },
  REVOLVING_LINE: {
    subtitle: 'Берите деньги частями для регулярных закупок',
    badgeColor: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    icon: Repeat,
    amountLabel: 'от 300 000 до 5 000 000 ₽',
    termLabel: 'линия 24 мес. (транш 3–6 мес.)',
    rateLabel: '23% годовых',
    rateNote: 'неиспользованный лимит 0 ₽',
    repayment:
      'По каждой полученной сумме — ежемесячно равными долями основного долга плюс проценты. Погашенная часть основного долга снова становится доступной.',
    requirements:
      'ИП или ООО, бизнес работает от 12 месяцев; нужны сведения об оборотах за последние 6 месяцев и действующих обязательствах.',
    example:
      'При лимите 2 млн ₽ вы взяли 500 000 ₽. Осталось доступно 1,5 млн ₽. Вернули 200 000 ₽ основного долга — доступная сумма выросла до 1,7 млн ₽.',
    advice:
      'Выбирайте этот продукт, если деньги нужны регулярно, но брать всю сумму сразу неудобно.',
  },
  EQUIPMENT: {
    subtitle: 'На оборудование, ремонт и расширение бизнеса',
    badgeColor: 'bg-purple-50 text-purple-700 border-purple-200',
    icon: Wrench,
    amountLabel: 'от 500 000 до 5 000 000 ₽',
    termLabel: 'от 24 до 60 месяцев',
    rateLabel: '22% годовых',
    rateNote: 'на остаток долга',
    repayment: 'Ежемесячно равными платежами.',
    requirements:
      'ИП или ООО, бизнес работает от 12 месяцев; нужны сведения о финансах за последние 12 месяцев, цель кредита и бюджет покупки или проекта.',
    example:
      'Пекарня покупает оборудование за 2 млн ₽ и планирует возвращать кредит в течение трёх лет.',
    advice:
      'Выбирайте этот продукт, если вложение рассчитано на несколько лет. Более длительный срок уменьшает ежемесячный платёж, но увеличивает общую переплату.',
  },
  GOV_CONTRACT: {
    subtitle: 'На исполнение контракта до получения оплаты от заказчика',
    badgeColor: 'bg-indigo-50 text-indigo-700 border-indigo-200',
    icon: Landmark,
    amountLabel: 'от 300 000 до 10 000 000 ₽',
    termLabel: 'до 12 месяцев',
    rateLabel: '21% годовых',
    rateNote: 'невозобновляемый лимит',
    repayment:
      'Проценты — ежемесячно; поступления по контракту направляются на погашение долга. Возврат — не позднее 30 дней после даты окончательной оплаты по контракту.',
    requirements:
      'ИП или ООО, бизнес работает от 12 месяцев; заключённый контракт по 44-ФЗ (лимит до 70% суммы контракта за вычетом аванса). Лимит не возобновляется.',
    example:
      'Компания должна поставить оборудование школе. Закупку нужно оплатить сейчас, а заказчик перечислит деньги после приёмки.',
    advice:
      'Выбирайте этот продукт, если финансирование нужно для исполнения уже заключённого госконтракта. Для участия в закупке или обеспечения заявки не предназначен.',
  },
};

const SELECTION_GUIDE = [
  {
    need: 'Одна сумма на закупку или текущие расходы',
    productName: 'Оборотный кредит',
    code: 'WORKING_CAPITAL',
  },
  {
    need: 'Оплатить сейчас, вернуть из ближайшего поступления',
    productName: 'Овердрафт',
    code: 'OVERDRAFT',
  },
  {
    need: 'Регулярно брать и возвращать деньги частями',
    productName: 'Возобновляемая кредитная линия',
    code: 'REVOLVING_LINE',
  },
  {
    need: 'Распределить крупные расходы на несколько лет',
    productName: 'Кредит на длительный срок',
    code: 'EQUIPMENT',
  },
  {
    need: 'Исполнить заключённый государственный контракт',
    productName: 'Кредитная линия на госконтракт',
    code: 'GOV_CONTRACT',
  },
];

export default function ProductsPage({
  products,
  applications,
  selectedProductCode,
  setSelectedProductCode,
  onCreateApplication,
  onOpenApplication,
  isCreating,
}) {
  const [modalProduct, setModalProduct] = useState(null);
  const [needSelectWarning, setNeedSelectWarning] = useState(false);
  const productsSectionRef = useRef(null);

  // Блокировка прокрутки фона при открытии модального окна «Подробнее»
  useEffect(() => {
    if (modalProduct) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => {
      document.body.style.overflow = '';
    };
  }, [modalProduct]);

  const scrollToProducts = () => {
    productsSectionRef.current?.scrollIntoView({
      behavior: 'smooth',
      block: 'start',
    });
  };

  const handleHeroApplyClick = () => {
    if (!selectedProductCode) {
      setNeedSelectWarning(true);
      scrollToProducts();
      return;
    }
    onCreateApplication(selectedProductCode);
  };

  const handleSelectCard = (code) => {
    setSelectedProductCode(code);
    setNeedSelectWarning(false);
  };

  return (
    <div className="space-y-8 pb-16">
      {/* ================= ЛЕНДИНГ-БАННЕР ================= */}
      <div className="space-y-4">
        <div className="bg-white rounded-[32px] p-8 sm:p-12 border border-gray-200/80 shadow-xs flex flex-col md:flex-row items-center justify-between gap-8 overflow-hidden relative">
          <div className="max-w-xl space-y-6 z-10">
            <div className="space-y-3">
              <h1 className="text-3xl sm:text-5xl font-extrabold tracking-tight text-[#121214] leading-tight">
                Кредиты для бизнеса
              </h1>
              <p className="text-base sm:text-lg text-gray-600">
                5 демонстрационных продуктов для ИП и ООО без залога, поручительства и комиссий за выдачу
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-3.5 pt-2">
              <button
                onClick={scrollToProducts}
                className="py-3.5 px-8 rounded-2xl bg-[#EF3124] hover:bg-[#D92B1E] text-white font-semibold text-sm sm:text-base shadow-sm hover:shadow-md transition-all cursor-pointer"
              >
                Подобрать кредит
              </button>

              <button
                onClick={handleHeroApplyClick}
                disabled={isCreating}
                className="py-3.5 px-8 rounded-2xl bg-white hover:bg-gray-50 text-[#121214] border border-gray-300 font-semibold text-sm sm:text-base transition-all cursor-pointer disabled:opacity-60"
              >
                {selectedProductCode
                  ? 'Заполнить заявку по выбранному продукту'
                  : 'Заполнить заявку'}
              </button>
            </div>
          </div>

          {/* Правая часть: Аккуратная картинка с импортом из src/assets */}
          <div className="relative w-64 h-52 sm:w-80 sm:h-64 shrink-0 flex items-center justify-center select-none z-10">
            <img
              src={walletImg}
              alt="Кредиты для бизнеса"
              className="w-full h-full object-contain transition-transform duration-300 hover:scale-105"
            />
          </div>
        </div>

        {/* 4 плашки с ключевыми параметрами линейки */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="bg-white rounded-3xl p-6 border border-gray-200/80 shadow-2xs">
            <div className="text-xl sm:text-2xl font-extrabold text-[#121214]">
              до 10 млн ₽
            </div>
            <div className="text-xs sm:text-sm text-gray-500 mt-1">
              максимальный лимит
            </div>
          </div>

          <div className="bg-white rounded-3xl p-6 border border-gray-200/80 shadow-2xs">
            <div className="text-xl sm:text-2xl font-extrabold text-[#121214]">
              до 60 месяцев
            </div>
            <div className="text-xs sm:text-sm text-gray-500 mt-1">
              максимальный срок
            </div>
          </div>

          <div className="bg-white rounded-3xl p-6 border border-gray-200/80 shadow-2xs">
            <div className="text-xl sm:text-2xl font-extrabold text-[#121214]">
              от 21% годовых
            </div>
            <div className="text-xs sm:text-sm text-gray-500 mt-1">
              демонстрационная ставка
            </div>
          </div>

          <div className="bg-white rounded-3xl p-6 border border-gray-200/80 shadow-2xs">
            <div className="text-xl sm:text-2xl font-extrabold text-[#121214]">
              Без залога и комиссий
            </div>
            <div className="text-xs sm:text-sm text-gray-500 mt-1">
              для ИП и ООО в рублях
            </div>
          </div>
        </div>
      </div>

      {/* Шпаргалка «Как выбрать подходящий продукт» */}
      <div className="bg-white rounded-3xl p-6 sm:p-8 border border-gray-200/90 shadow-xs">
        <h2 className="text-lg sm:text-xl font-bold text-gray-900 mb-1">
          Как выбрать подходящий продукт
        </h2>
        <p className="text-xs sm:text-sm text-gray-500 mb-4">
          Нажмите на вашу задачу — соответствующая карточка продукта автоматически выделится ниже:
        </p>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-2.5">
          {SELECTION_GUIDE.map((row) => {
            const isSelected = selectedProductCode === row.code;
            return (
              <button
                key={row.code}
                onClick={() => {
                  handleSelectCard(row.code);
                  scrollToProducts();
                }}
                className={`text-left p-3.5 rounded-2xl border transition-all flex flex-col justify-between gap-2 cursor-pointer ${
                  isSelected
                    ? 'bg-red-50/70 border-[#EF3124] ring-1 ring-[#EF3124]'
                    : 'bg-gray-50/70 border-gray-200 hover:bg-gray-100'
                }`}
              >
                <span className="text-xs text-gray-600 leading-snug">
                  {row.need}
                </span>
                <span className="text-xs font-bold text-[#121214] flex items-center justify-between gap-1 pt-1 border-t border-gray-200/60">
                  <span>{row.productName}</span>
                  <ArrowRight className="w-3.5 h-3.5 text-[#EF3124] shrink-0" />
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {/* ================= СЕКЦИЯ КАРТОЧЕК ПРОДУКТОВ (ПО 3 В РЯД ОДНОГО РАЗМЕРА) ================= */}
      <div ref={productsSectionRef} className="pt-2 space-y-6 scroll-mt-24">
        <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4">
          <div>
            <h2 className="text-2xl sm:text-3xl font-bold tracking-tight text-gray-900">
              Выберите кредитный продукт
            </h2>
            <p className="mt-1 text-gray-600 text-sm sm:text-base">
              Нажмите на карточку, чтобы выбрать продукт, а затем перейдите к заполнению заявки.
            </p>
          </div>

          {selectedProductCode && (
            <button
              onClick={() => onCreateApplication(selectedProductCode)}
              disabled={isCreating}
              className="shrink-0 py-3 px-6 rounded-2xl bg-[#EF3124] hover:bg-[#D92B1E] text-white font-bold text-sm shadow-sm flex items-center gap-2 transition-all cursor-pointer"
            >
              <span>Заполнить заявку по выбранному</span>
              <ArrowRight className="w-4 h-4" />
            </button>
          )}
        </div>

        {needSelectWarning && (
          <div className="p-4 rounded-2xl bg-red-50 border border-[#EF3124]/40 text-[#121214] flex items-center gap-3 animate-fadeIn">
            <AlertCircle className="w-5 h-5 text-[#EF3124] shrink-0" />
            <p className="text-xs sm:text-sm font-medium">
              Пожалуйста, сначала <span className="font-bold">выберите одну из кредитных карточек ниже</span>, после чего откроется форма заявки.
            </p>
          </div>
        )}

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 items-stretch">
          {products.map((product) => {
            const meta =
              PRODUCT_DETAILS[product.code] || PRODUCT_DETAILS.WORKING_CAPITAL;
            const Icon = meta.icon;
            const isSelected = selectedProductCode === product.code;

            return (
              <div
                key={product.code}
                onClick={() => handleSelectCard(product.code)}
                className={`h-full bg-white rounded-3xl p-6 border transition-all duration-200 flex flex-col justify-between cursor-pointer ${
                  isSelected
                    ? 'border-[#EF3124] ring-2 ring-[#EF3124]/20 shadow-md'
                    : 'border-gray-200/90 shadow-xs hover:shadow-md hover:border-gray-300'
                }`}
              >
                <div className="flex flex-col flex-1">
                  <div className="flex items-start justify-between gap-2 mb-4">
                    <div
                      className={`w-11 h-11 rounded-2xl flex items-center justify-center shrink-0 transition-colors ${
                        isSelected
                          ? 'bg-[#EF3124] text-white'
                          : 'bg-gray-100 text-gray-800'
                      }`}
                    >
                      <Icon className="w-5 h-5" />
                    </div>

                    <span
                      className={`text-[11px] font-semibold px-2.5 py-1 rounded-full border text-right line-clamp-1 ${
                        isSelected
                          ? 'bg-red-50 text-[#EF3124] border-red-200'
                          : meta.badgeColor
                      }`}
                    >
                      {isSelected ? '✓ Карточка выбрана' : meta.rateNote}
                    </span>
                  </div>

                  {/* Полное название без обрезки + единая высота для ровной сетки */}
                  <h3 className="text-base sm:text-lg font-bold text-gray-900 mb-1 leading-snug min-h-[2.75rem] flex items-center">
                    {product.name}
                  </h3>
                  <p className="text-xs font-medium text-[#EF3124] mb-2.5 line-clamp-1">
                    {meta.subtitle}
                  </p>
                  <p className="text-xs sm:text-sm text-gray-600 mb-5 line-clamp-3 min-h-[3.75rem] leading-relaxed">
                    {product.description}
                  </p>

                  <div className="grid grid-cols-2 gap-2.5 mb-6 mt-auto">
                    <div className="bg-gray-50 p-3 rounded-2xl border border-gray-100">
                      <div className="flex items-center gap-1 text-[11px] text-gray-500 mb-0.5">
                        <Wallet className="w-3 h-3" />
                        <span>Сумма / лимит</span>
                      </div>
                      <div className="font-bold text-xs text-gray-900 line-clamp-1">
                        {meta.amountLabel}
                      </div>
                    </div>

                    <div className="bg-gray-50 p-3 rounded-2xl border border-gray-100">
                      <div className="flex items-center gap-1 text-[11px] text-gray-500 mb-0.5">
                        <Clock className="w-3 h-3" />
                        <span>Срок</span>
                      </div>
                      <div className="font-bold text-xs text-gray-900 line-clamp-1">
                        {meta.termLabel}
                      </div>
                    </div>

                    <div className="bg-gray-50 p-3 rounded-2xl border border-gray-100">
                      <div className="flex items-center gap-1 text-[11px] text-gray-500 mb-0.5">
                        <Percent className="w-3 h-3" />
                        <span>Ставка</span>
                      </div>
                      <div className="font-bold text-xs text-gray-900 line-clamp-1">
                        {meta.rateLabel}
                      </div>
                    </div>

                    <div className="bg-gray-50 p-3 rounded-2xl border border-gray-100">
                      <div className="flex items-center gap-1 text-[11px] text-gray-500 mb-0.5">
                        <CheckCircle2 className="w-3 h-3" />
                        <span>Бизнес</span>
                      </div>
                      <div className="font-bold text-xs text-gray-900 line-clamp-1">
                        от {product.terms.minBusinessAgeMonths} мес.
                      </div>
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-2.5 pt-2 border-t border-gray-100">
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      setModalProduct({ product, meta });
                    }}
                    className="py-2.5 px-3.5 rounded-xl border border-gray-300 text-gray-700 font-semibold text-xs hover:bg-gray-50 transition-all flex items-center justify-center gap-1 cursor-pointer"
                  >
                    <Info className="w-3.5 h-3.5" />
                    <span>Подробнее</span>
                  </button>

                  {isSelected ? (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onCreateApplication(product.code);
                      }}
                      disabled={isCreating}
                      className="flex-1 py-2.5 px-4 rounded-xl bg-[#EF3124] hover:bg-[#D92B1E] text-white font-semibold text-xs shadow-xs transition-all flex items-center justify-center gap-1.5 cursor-pointer"
                    >
                      {isCreating ? (
                        <Loader2 className="w-3.5 h-3.5 animate-spin" />
                      ) : (
                        <>
                          <span>Заполнить заявку</span>
                          <ArrowRight className="w-3.5 h-3.5" />
                        </>
                      )}
                    </button>
                  ) : (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        handleSelectCard(product.code);
                      }}
                      className="flex-1 py-2.5 px-4 rounded-xl bg-gray-900 hover:bg-[#EF3124] text-white font-semibold text-xs transition-all flex items-center justify-center gap-1.5 cursor-pointer"
                    >
                      <span>Выбрать карточку</span>
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Недавние заявки компании (перенесены под карточки с якорем для скролла) */}
      <div id="recent-applications" className="scroll-mt-24">
        {applications.length > 0 && (
          <div className="bg-white rounded-3xl p-5 sm:p-6 border border-gray-200/90 shadow-xs">
            <div className="flex items-center gap-2 text-sm font-bold text-gray-900 mb-3">
              <History className="w-4 h-4 text-[#EF3124]" />
              <span>Ваши недавние заявки ({applications.length})</span>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {applications.slice(0, 4).map((app) => (
                <div
                  key={app.id}
                  onClick={() => onOpenApplication(app.id)}
                  className="p-3.5 rounded-2xl bg-gray-50 hover:bg-red-50/40 border border-gray-200 hover:border-[#EF3124]/40 transition-all flex items-center justify-between cursor-pointer"
                >
                  <div>
                    <div className="font-semibold text-sm text-gray-900">
                      {app.productName}
                    </div>
                    <div className="text-xs text-gray-500">
                      Сумма: {formatRubles(app.amount)} • Срок:{' '}
                      {app.termMonths ? `${app.termMonths} мес.` : '—'} • v{app.version}
                    </div>
                  </div>
                  <span className="text-xs font-semibold text-[#EF3124] flex items-center gap-1">
                    <span>Открыть</span>
                    <ArrowRight className="w-3.5 h-3.5" />
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Общие условия */}
      <div className="bg-white rounded-3xl p-6 sm:p-8 border border-gray-200/90 shadow-xs space-y-3">
        <div className="flex items-center gap-2 text-base font-bold text-gray-900">
          <ShieldCheck className="w-5 h-5 text-[#EF3124]" />
          <span>Общие условия для всех пяти продуктов</span>
        </div>
        <p className="text-xs sm:text-sm text-gray-600 leading-relaxed">
          Все продукты доступны в рублях для ИП и ООО, зарегистрированных в России. В рамках учебного проекта залог и поручительство не требуются. Комиссий за выдачу и обслуживание кредита нет; обязательная страховка отсутствует. Досрочное погашение — без комиссии, с оплатой процентов за фактическое время использования денег.
        </p>
        <p className="text-xs text-gray-500 leading-relaxed">
          Для рассмотрения заявки нужны полные и корректные сведения и отсутствие текущей просроченной задолженности. Решение принимается автоматически с учётом финансовых показателей, действующих обязательств и оценки риска. Соответствие указанным требованиям не гарантирует одобрение.
        </p>
      </div>

      {/* Модальное окно «Подробнее» */}
      {modalProduct && (
        <div
          onClick={() => setModalProduct(null)}
          className="fixed inset-0 z-50 bg-black/50 backdrop-blur-xs flex items-center justify-center p-4 animate-fadeIn"
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="bg-white rounded-3xl max-w-xl w-full p-6 sm:p-8 border border-gray-200 shadow-2xl space-y-5 max-h-[90vh] overflow-y-auto"
          >
            <div className="flex items-start justify-between gap-4">
              <div>
                <span className="text-xs font-semibold text-[#EF3124]">
                  {modalProduct.meta.subtitle}
                </span>
                <h3 className="text-xl sm:text-2xl font-bold text-gray-900 mt-0.5">
                  {modalProduct.product.name}
                </h3>
              </div>
              <button
                onClick={() => setModalProduct(null)}
                className="p-2 rounded-xl bg-gray-100 hover:bg-gray-200 text-gray-600 cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <p className="text-sm text-gray-700 leading-relaxed">
              {modalProduct.product.description}
            </p>

            <div className="space-y-3 text-xs sm:text-sm bg-gray-50 p-4 rounded-2xl border border-gray-200/80">
              <div>
                <span className="font-bold text-gray-900">Сумма / лимит: </span>
                <span className="text-gray-700">{modalProduct.meta.amountLabel}</span>
              </div>
              <div>
                <span className="font-bold text-gray-900">Срок: </span>
                <span className="text-gray-700">{modalProduct.meta.termLabel}</span>
              </div>
              <div>
                <span className="font-bold text-gray-900">Ставка: </span>
                <span className="text-gray-700">
                  {modalProduct.meta.rateLabel} ({modalProduct.meta.rateNote})
                </span>
              </div>
              <div>
                <span className="font-bold text-gray-900">Погашение: </span>
                <span className="text-gray-700">{modalProduct.meta.repayment}</span>
              </div>
              <div>
                <span className="font-bold text-gray-900">Требования: </span>
                <span className="text-gray-700">{modalProduct.meta.requirements}</span>
              </div>
            </div>

            <div className="bg-red-50/60 p-4 rounded-2xl border border-red-200/70 space-y-2 text-xs sm:text-sm">
              <div className="font-bold text-gray-900 flex items-center gap-1.5">
                <Sparkles className="w-4 h-4 text-[#EF3124]" />
                <span>Пример и рекомендация</span>
              </div>
              <p className="text-gray-700">{modalProduct.meta.example}</p>
              <p className="text-gray-600 italic">{modalProduct.meta.advice}</p>
            </div>

            <div className="flex items-center justify-end gap-3 pt-2">
              <button
                onClick={() => setModalProduct(null)}
                className="py-2.5 px-5 rounded-xl border border-gray-300 text-xs sm:text-sm font-semibold text-gray-700 hover:bg-gray-50 cursor-pointer"
              >
                Закрыть
              </button>
              <button
                onClick={() => {
                  const code = modalProduct.product.code;
                  setModalProduct(null);
                  handleSelectCard(code);
                  onCreateApplication(code);
                }}
                className="py-2.5 px-6 rounded-xl bg-[#EF3124] hover:bg-[#D92B1E] text-white text-xs sm:text-sm font-semibold flex items-center gap-2 cursor-pointer"
              >
                <span>Выбрать и заполнить заявку</span>
                <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}