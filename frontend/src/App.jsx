import { useState, useEffect, useRef, useCallback } from 'react';
import Header from './components/Header';
import DisclaimerBanner from './components/DisclaimerBanner';
import AssistantWidget from './components/AssistantWidget';
import ProductsPage from './pages/ProductsPage';
import ApplicationPage from './pages/ApplicationPage';
import {
  initSessionAndGetUser,
  fetchProducts,
  fetchApplications,
  createApplication,
  fetchApplicationById,
  getApiMode,
  setApiMode,
  storageKey,
} from './api/client';

export default function App() {
  const [activePage, setActivePage] = useState('products');
  const [apiModeState, setApiModeState] = useState(getApiMode());

  const [userContext, setUserContext] = useState(null);
  const [products, setProducts] = useState([]);
  const [applications, setApplications] = useState([]);
  const [currentApp, setCurrentApp] = useState(null);
  const [selectedProductCode, setSelectedProductCode] = useState(null);
  const [focusedField, setFocusedField] = useState(null);

  const [isLoading, setIsLoading] = useState(true);
  const [isCreating, setIsCreating] = useState(false);
  const [connectionError, setConnectionError] = useState(null);

  const [bootstrapAttempt, setBootstrapAttempt] = useState(0);
  const modeRef = useRef(apiModeState);
  const creating = useRef(false);
  modeRef.current = apiModeState;

  useEffect(() => {
    let active = true;
    setIsLoading(true); setConnectionError(null); setProducts([]); setApplications([]);
    setCurrentApp(null); setSelectedProductCode(null); setFocusedField(null); setActivePage('products');
    const bootstrap = async () => {
      try {
        const me = await initSessionAndGetUser();
        if (!active) return;
        setUserContext(me);
        const [catalog, page] = await Promise.all([fetchProducts(), fetchApplications()]);
        if (!active) return;
        setProducts(catalog.items || []); setApplications(page.items || []);
        const savedId = localStorage.getItem(storageKey('active_app_id'));
        let card = null;
        if (savedId) {
          try { card = await fetchApplicationById(savedId); }
          catch (error) { if (error.status === 404) localStorage.removeItem(storageKey('active_app_id')); else throw error; }
        }
        if (!card && page.items?.length) card = await fetchApplicationById(page.items[0].id);
        if (active) setCurrentApp(card);
      } catch (error) {
        if (active) setConnectionError(error.title || 'Не удалось подключиться к Java API. Проверьте запуск бэкенда или выберите моки в шапке.');
      } finally { if (active) setIsLoading(false); }
    };
    bootstrap();
    return () => { active = false; };
  }, [apiModeState, bootstrapAttempt]);

  const handleToggleApiMode = () => {
    const next = apiModeState === 'mock' ? 'real' : 'mock';
    modeRef.current = next;
    setIsLoading(true);
    setUserContext(null);
    setApiMode(next); setApiModeState(next);
  };

  const refreshApplications = useCallback(async () => {
    const mode = apiModeState;
    try {
      const page = await fetchApplications();
      if (modeRef.current === mode) setApplications(page.items || []);
    } catch { return; }
  }, [apiModeState]);

  const handleCreateApplication = async productCode => {
    if (creating.current) return;
    const mode = apiModeState;
    creating.current = true; setIsCreating(true); setSelectedProductCode(productCode);
    try {
      const created = await createApplication(productCode);
      if (modeRef.current !== mode) return;
      setCurrentApp(created); setFocusedField(null);
      localStorage.setItem(storageKey('active_app_id'), created.id);
      setActivePage('application');
      refreshApplications();
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (error) { if (modeRef.current === mode) alert(error.title || 'Не удалось создать черновик. Повторите запрос.'); }
    finally { creating.current = false; setIsCreating(false); }
  };

  const handleOpenApplication = async id => {
    const mode = apiModeState;
    try {
      const card = await fetchApplicationById(id);
      if (modeRef.current !== mode) return;
      setCurrentApp(card); setSelectedProductCode(card.product.code); setFocusedField(null);
      localStorage.setItem(storageKey('active_app_id'), card.id);
      setActivePage('application'); window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (error) { if (modeRef.current === mode) alert(error.title || 'Не удалось открыть заявку'); }
  };

  const updateApplication = useCallback(updated => {
    if (modeRef.current !== apiModeState) return;
    setCurrentApp(previous => previous?.id === updated.id ? updated : previous);
    refreshApplications();
  }, [apiModeState, refreshApplications]);

  return (
    <div className="min-h-screen bg-[#F4F5F7] text-[#121214]">
      <Header
        activePage={activePage}
        setActivePage={setActivePage}
        userContext={userContext}
        currentApp={currentApp}
        apiMode={apiModeState}
        onToggleApiMode={handleToggleApiMode}
      />

      <main className="max-w-6xl mx-auto px-4 sm:px-6 py-8">
        <DisclaimerBanner />

        {connectionError && (
          <div className="mb-6 p-4 rounded-2xl bg-red-50 border border-red-200 text-red-800 text-sm flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <span>{connectionError}</span>
            <button
              onClick={() => setBootstrapAttempt(value => value + 1)}
              className="shrink-0 py-2 px-4 rounded-xl bg-[#EF3124] text-white text-xs font-semibold cursor-pointer"
            >
              Повторить подключение
            </button>
          </div>
        )}

        {isLoading ? (
          <div className="bg-white rounded-3xl p-12 border border-gray-200 text-center text-gray-500 text-sm">
            Загрузка демо-сессии и каталога продуктов...
          </div>
        ) : activePage === 'products' ? (
          <ProductsPage
            products={products}
            applications={applications}
            selectedProductCode={selectedProductCode}
            setSelectedProductCode={setSelectedProductCode}
            onCreateApplication={handleCreateApplication}
            onOpenApplication={handleOpenApplication}
            isCreating={isCreating}
          />
        ) : currentApp ? (
          <ApplicationPage
            key={`${apiModeState}:${currentApp.id}`}
            application={currentApp}
            setApplication={updateApplication}
            onBackToProducts={() => setActivePage('products')}
            onCreateNewWithProduct={handleCreateApplication}
            onFocusField={setFocusedField}
            apiMode={apiModeState}
          />
        ) : (
          <div className="bg-white rounded-3xl p-10 border border-gray-200 text-center space-y-4">
            <h2 className="text-xl font-bold">Сначала выберите кредитный продукт</h2>
            <p className="text-sm text-gray-600">
              Перейдите в каталог, выберите подходящую кредитную карточку и нажмите «Заполнить заявку».
            </p>
            <button
              onClick={() => setActivePage('products')}
              className="py-2.5 px-5 rounded-xl bg-[#EF3124] text-white text-sm font-semibold cursor-pointer"
            >
              Перейти к выбору продукта
            </button>
          </div>
        )}
      </main>

      <AssistantWidget
        key={`${apiModeState}:${userContext?.user?.id || 'loading'}`}
        enabled={!isLoading && !connectionError}
        apiMode={apiModeState}
        products={products}
        userId={userContext?.user?.id || 'demo'}
        applicationId={activePage === 'application' ? currentApp?.id || null : null}
        productCode={activePage === 'application' ? currentApp?.product?.code : selectedProductCode}
        focusedFieldCode={activePage === 'application' ? focusedField : null}
        onSelectRecommendedProduct={handleCreateApplication}
      />
    </div>
  );
}
