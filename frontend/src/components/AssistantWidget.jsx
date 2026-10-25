import { useState, useEffect, useRef, useCallback } from 'react';
import {
  Bot,
  Send,
  Sparkles,
  ArrowRight,
  Loader2,
  MessageSquareText,
  X,
} from 'lucide-react';
import {
  initAssistantConversation,
  fetchAssistantMessages,
  sendAssistantMessage,
  generateUUID,
} from '../api/client';

const QUICK_PROMPTS = [
  'Хочу купить печь для пекарни. Какой кредит выбрать?',
  'Нужны деньги сейчас, чтобы оплатить закупку товара',
  'Что указывать в среднемесячных поступлениях?',
  'Что входит в среднемесячные расходы?',
];

export default function AssistantWidget({
  enabled = false,
  apiMode,
  products = [],
  userId,
  applicationId = null,
  productCode = null,
  focusedFieldCode = null,
  onSelectRecommendedProduct,
}) {
  const [isOpen, setIsOpen] = useState(false);

  const conversationKey = `alfa_${apiMode}_${userId}_conversation_id`;
  const conversation = useRef(localStorage.getItem(conversationKey));
  const creating = useRef(null);
  const sending = useRef(false);
  const pendingMessage = useRef(null);
  const [messages, setMessages] = useState([]);
  const [inputText, setInputText] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(true);
  const [errorText, setErrorText] = useState(null);
  const chatEndRef = useRef(null);

  const ensureConversation = useCallback(async () => {
    if (conversation.current) return conversation.current;
    if (!creating.current) {
      creating.current = initAssistantConversation().then(created => {
        conversation.current = created.id;
        localStorage.setItem(conversationKey, created.id);
        return created.id;
      }).finally(() => { creating.current = null; });
    }
    return creating.current;
  }, [conversationKey]);

  useEffect(() => {
    if (!enabled) return;
    let active = true;
    setLoadingHistory(true);
    const load = async () => {
      try {
        let id = await ensureConversation();
        if (!active) return;
        let page;
        try { page = await fetchAssistantMessages(id); }
        catch (error) {
          if (!active) return;
          if (error.status !== 404) throw error;
          conversation.current = null; localStorage.removeItem(conversationKey);
          id = await ensureConversation(); page = await fetchAssistantMessages(id);
        }
        const history = [...page.items];
        while (page.hasMore && active) {
          page = await fetchAssistantMessages(id, page.lastSequence);
          history.push(...page.items);
        }
        if (active) { setMessages(history); setErrorText(null); }
      } catch (error) { if (active) setErrorText(error.title || 'Не удалось загрузить диалог. Попробуйте отправить сообщение ещё раз.'); }
      finally { if (active) setLoadingHistory(false); }
    };
    load();
    return () => { active = false; };
  }, [enabled, ensureConversation, conversationKey]);

  useEffect(() => {
    if (isOpen) chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isOpen]);

  const handleSend = async customText => {
    const text = (customText ?? inputText).trim();
    if (!enabled || loadingHistory || !text || sending.current) return;
    sending.current = true; setIsSending(true); setErrorText(null);
    if (!customText) setInputText('');
    const context = { applicationId, productCode, fieldCode: focusedFieldCode };
    const signature = JSON.stringify({ text, context });
    if (pendingMessage.current?.signature !== signature) pendingMessage.current = { signature, key: generateUUID() };
    try {
      const id = await ensureConversation();
      const exchange = await sendAssistantMessage(id, text, context, pendingMessage.current.key);
      pendingMessage.current = null;
      setMessages(previous => [...previous, exchange.userMessage, exchange.assistantMessage]);
    } catch (error) {
      if (error.status === 404) { conversation.current = null; localStorage.removeItem(conversationKey); }
      if (!customText) setInputText(text);
      setErrorText(error.title || 'Помощник временно недоступен. Повторите запрос.');
    } finally { sending.current = false; setIsSending(false); }
  };

  return (
    <div className="fixed bottom-6 right-6 z-50">
      {isOpen ? (

        <div className="w-[calc(100vw-2rem)] sm:w-[390px] bg-white rounded-3xl border border-gray-200 shadow-2xl flex flex-col overflow-hidden animate-fadeIn">

          <div className="bg-[#121214] text-white p-4 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-xl bg-[#EF3124] flex items-center justify-center shrink-0">
                <Bot className="w-5 h-5 text-white" />
              </div>
              <div>
                <h3 className="font-bold text-sm">Умный ИИ-помощник</h3>
                <p className="text-[11px] text-gray-400">
                  {focusedFieldCode
                    ? `Поле: ${focusedFieldCode.replace('form.', '')}`
                    : 'Консультация по продуктам и анкете'}
                </p>
              </div>
            </div>

            <button
              onClick={() => setIsOpen(false)}
              title="Закрыть помощника"
              className="p-2 rounded-xl text-gray-400 hover:text-white hover:bg-white/10 transition-all cursor-pointer"
            >
              <X className="w-5 h-5" />
            </button>
          </div>


          <div className="p-3.5 bg-gray-50 border-b border-gray-100">
            <div className="text-[11px] font-semibold text-gray-500 mb-2 flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-[#EF3124]" />
              <span>Быстрые вопросы:</span>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {QUICK_PROMPTS.map((prompt) => (
                <button
                  key={prompt}
                  onClick={() => handleSend(prompt)}
                  disabled={!enabled || loadingHistory || isSending}
                  className="text-left text-[11px] bg-white hover:bg-red-50/60 hover:border-[#EF3124]/40 text-gray-700 px-2.5 py-1.5 rounded-xl border border-gray-200 transition-all cursor-pointer disabled:opacity-50"
                >
                  {prompt}
                </button>
              ))}
            </div>
          </div>


          <div className="p-4 space-y-3 h-72 overflow-y-auto bg-white">
            {messages.length === 0 ? (
              <div className="text-center py-8 px-4 text-gray-400 text-xs space-y-2">
                <MessageSquareText className="w-7 h-7 mx-auto text-gray-300" />
                <p>
                  Задайте вопрос своими словами или нажмите на быструю подсказку выше. Помощник объяснит условия и поможет заполнить заявку.
                </p>
              </div>
            ) : (
              messages.map((msg) => (
                <div
                  key={msg.id}
                  className={`flex flex-col ${
                    msg.role === 'USER' ? 'items-end' : 'items-start'
                  }`}
                >
                  <div
                    className={`max-w-[90%] rounded-2xl px-3.5 py-2.5 text-xs sm:text-sm leading-relaxed ${
                      msg.role === 'USER'
                        ? 'bg-[#121214] text-white rounded-br-xs'
                        : 'bg-gray-100 text-gray-800 rounded-bl-xs'
                    }`}
                  >
                    <p>{msg.text}</p>

                    {msg.recommendedProductCode && onSelectRecommendedProduct && (
                      <button
                        onClick={() => {
                          onSelectRecommendedProduct(msg.recommendedProductCode);
                          setIsOpen(false);
                        }}
                        className="mt-2.5 w-full py-2 px-3 rounded-xl bg-[#EF3124] hover:bg-[#D92B1E] text-white text-xs font-semibold flex items-center justify-center gap-1.5 transition-all cursor-pointer"
                      >
                        <span>
                          Оформить «
                          {products.find(product => product.code === msg.recommendedProductCode)?.name || 'Выбранный продукт'}
                          »
                        </span>
                        <ArrowRight className="w-3.5 h-3.5" />
                      </button>
                    )}
                  </div>
                </div>
              ))
            )}
            <div ref={chatEndRef} />
          </div>


          {errorText && (
            <div className="px-4 py-2 bg-red-50 text-red-700 text-xs border-t border-red-100">
              {errorText}
            </div>
          )}


          <form
            onSubmit={(e) => {
              e.preventDefault();
              handleSend();
            }}
            className="p-3 bg-gray-50 border-t border-gray-200 flex items-center gap-2"
          >
            <input
              type="text"
              value={inputText}
              onChange={(e) => setInputText(e.target.value)}
              placeholder="Спросите помощника..."
              maxLength={2000}
              className="flex-1 bg-white text-xs sm:text-sm px-3.5 py-2.5 rounded-xl border border-gray-300 focus:outline-none focus:border-[#EF3124]"
            />
            <button
              type="submit"
              disabled={!enabled || loadingHistory || isSending || !inputText.trim()}
              className="p-2.5 rounded-xl bg-[#EF3124] hover:bg-[#D92B1E] text-white disabled:opacity-50 transition-all cursor-pointer"
            >
              {isSending ? (
                <Loader2 className="w-4 h-4 animate-spin" />
              ) : (
                <Send className="w-4 h-4" />
              )}
            </button>
          </form>
        </div>
      ) : (

        <button
          onClick={() => setIsOpen(true)}
          title="Открыть ИИ-помощника"
          className="group flex items-center gap-2.5 bg-[#121214] hover:bg-[#EF3124] text-white pl-4 pr-5 py-3.5 rounded-full shadow-lg hover:shadow-xl transition-all duration-200 cursor-pointer"
        >
          <div className="relative flex items-center justify-center">
            <Bot className="w-5 h-5" />
            <span className="absolute -top-1 -right-1 w-2.5 h-2.5 rounded-full bg-[#EF3124] group-hover:bg-white border-2 border-[#121214]" />
          </div>
          <span className="text-xs sm:text-sm font-bold tracking-tight">
            ИИ-помощник
          </span>
        </button>
      )}
    </div>
  );
}
