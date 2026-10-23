import { useState, useEffect } from 'react';
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

  const bootstrapApp = async () => {
    setIsLoading(true);
    setConnectionError(null);
    try {
      const me = await initSessionAndGetUser();
      setUserContext(me);

      const [prodList, appPage] = await Promise.all([
        fetchProducts(),
        fetchApplications(),
      ]);

      setProducts(prodList.items || []);
      setApplications(appPage.items || []);

      const savedAppId = localStorage.getItem('alfa_active_app_id');
      if (savedAppId) {
        try {
          const fullApp = await fetchApplicationById(savedAppId);
          setCurrentApp(fullApp);
        } catch {
          localStorage.removeItem('alfa_active_app_id');
        }
      } else if (appPage.items?.length > 0) {
        const firstApp = await fetchApplicationById(appPage.items[0].id);
        setCurrentApp(firstApp);
      }
    } catch (err) {
      setConnectionError(
        err?.title ||
          'Не удалось подключиться к Java-серверу (/api/v1). Проверьте, что бэкенд запущен, или переключитесь в режим «Моки OpenAPI» в шапке.'
      );
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    bootstrapApp();
  }, [apiModeState]);

  const handleToggleApiMode = () => {
    const next = apiModeState === 'mock' ? 'real' : 'mock';
    setApiMode(next);
    setApiModeState(next);
  };

  const handleCreateApplication = async (productCode) => {
    setSelectedProductCode(productCode);
    setIsCreating(true);
    try {
      const created = await createApplication(productCode);
      setCurrentApp(created);
      localStorage.setItem('alfa_active_app_id', created.id);
      const appPage = await fetchApplications();
      setApplications(appPage.items || []);
      setActivePage('application');
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (err) {
      alert(err?.title || 'Ошибка при создании черновика заявки');
    } finally {
      setIsCreating(false);
    }
  };

  const handleOpenApplication = async (appId) => {
    try {
      const fullApp = await fetchApplicationById(appId);
      setCurrentApp(fullApp);
      setSelectedProductCode(fullApp.product?.code || null);
      localStorage.setItem('alfa_active_app_id', fullApp.id);
      setActivePage('application');
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (err) {
      alert(err?.title || 'Не удалось открыть заявку');
    }
  };

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
              onClick={handleToggleApiMode}
              className="shrink-0 py-2 px-4 rounded-xl bg-[#EF3124] text-white text-xs font-semibold cursor-pointer"
            >
              Переключить на Моки OpenAPI
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
            application={currentApp}
            setApplication={(updated) => {
              setCurrentApp(updated);
              fetchApplications().then((p) => setApplications(p.items || []));
            }}
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
        applicationId={currentApp?.id || null}
        productCode={currentApp?.product?.code || selectedProductCode}
        focusedFieldCode={activePage === 'application' ? focusedField : null}
        onSelectRecommendedProduct={handleCreateApplication}
      />
    </div>
  );
}