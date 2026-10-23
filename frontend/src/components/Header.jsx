import { Building2, Server, Sparkles, FileText } from 'lucide-react';

const STATUS_BADGE = {
  DRAFT: { label: 'Черновик', cls: 'bg-gray-100 text-gray-700' },
  CHECKING: { label: 'Проверка...', cls: 'bg-blue-50 text-blue-700' },
  NEEDS_INFO: { label: 'Уточнение', cls: 'bg-amber-50 text-amber-700' },
  TECHNICAL_HOLD: { label: 'Сбой проверки', cls: 'bg-orange-50 text-orange-700' },
  APPROVED: { label: 'Одобрено', cls: 'bg-emerald-50 text-emerald-700' },
  DECLINED: { label: 'Отказано', cls: 'bg-red-50 text-red-700' },
};

export default function Header({
  activePage,
  setActivePage,
  userContext,
  currentApp,
  apiMode,
  onToggleApiMode,
}) {
  const company = userContext?.company;
  const user = userContext?.user;
  const statusInfo = currentApp ? STATUS_BADGE[currentApp.status] : null;

  return (
    <header className="sticky top-0 z-30 bg-white/95 backdrop-blur-md border-b border-gray-200">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 h-18 flex items-center justify-between gap-3">
        {/* Логотип */}
        <div
          onClick={() => setActivePage('products')}
          className="flex items-center gap-3 cursor-pointer"
        >
          <div className="w-10 h-10 rounded-xl bg-[#EF3124] flex items-center justify-center text-white font-bold text-xl shadow-sm">
            А
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="font-bold text-base sm:text-lg tracking-tight">
                Альфа Бизнес
              </span>
              <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-red-50 text-[#EF3124] border border-red-200">
                API v0.2
              </span>
            </div>
            <p className="text-xs text-gray-500 hidden sm:block">
              Умный кредитный кабинет предпринимателя
            </p>
          </div>
        </div>

        {/* Навигация: 1. Продукты | 2. Заявка */}
        <nav className="flex items-center bg-gray-100 p-1 rounded-xl border border-gray-200">
          <button
            onClick={() => setActivePage('products')}
            className={`px-3.5 py-2 rounded-lg text-xs sm:text-sm font-semibold transition-all cursor-pointer ${
              activePage === 'products'
                ? 'bg-white text-[#121214] shadow-xs'
                : 'text-gray-600 hover:text-gray-900'
            }`}
          >
            1. Продукты
          </button>
          <button
            onClick={() => setActivePage('application')}
            className={`px-3.5 py-2 rounded-lg text-xs sm:text-sm font-semibold transition-all flex items-center gap-2 cursor-pointer ${
              activePage === 'application'
                ? 'bg-white text-[#121214] shadow-xs'
                : 'text-gray-600 hover:text-gray-900'
            }`}
          >
            <FileText className="w-3.5 h-3.5 text-[#EF3124]" />
            <span>2. Заявка</span>
            {statusInfo && (
              <span className={`text-[10px] px-2 py-0.5 rounded-full font-semibold ${statusInfo.cls}`}>
                {statusInfo.label}
              </span>
            )}
          </button>
        </nav>

        {/* Правый блок: Переключатель режима API + Компания из /api/v1/me */}
        <div className="flex items-center gap-2.5">
          <button
            onClick={onToggleApiMode}
            title="Переключить между локальными моками контракта и реальным сервером Java (/api/v1)"
            className={`px-3 py-2 rounded-xl border text-xs font-semibold flex items-center gap-1.5 transition-all cursor-pointer ${
              apiMode === 'mock'
                ? 'bg-amber-50 border-amber-200 text-amber-800 hover:bg-amber-100'
                : 'bg-emerald-50 border-emerald-200 text-emerald-800 hover:bg-emerald-100'
            }`}
          >
            {apiMode === 'mock' ? (
              <>
                <Sparkles className="w-3.5 h-3.5" />
                <span>Моки OpenAPI</span>
              </>
            ) : (
              <>
                <Server className="w-3.5 h-3.5" />
                <span>Java API (/api/v1)</span>
              </>
            )}
          </button>

          {company && (
            <div className="hidden lg:flex items-center gap-2.5 bg-gray-50 px-3.5 py-1.5 rounded-xl border border-gray-200">
              <Building2 className="w-4 h-4 text-gray-500" />
              <div className="text-xs">
                <div className="font-semibold text-gray-900">
                  {company.legalForm} «{company.name}»
                </div>
                <div className="text-gray-500">
                  {user?.displayName} • с {company.registrationDate}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}