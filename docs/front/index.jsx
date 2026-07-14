import React, { useState, useEffect, useRef } from 'react';
import KbManager from './components/KbManager.jsx';
import KbDetail from './components/KbDetail.jsx';

const useChat = window.ZeroAI?.useChat || function () {
const [messages, setMessages] = React.useState([]);
const [status, setStatus] = React.useState('idle');
const sendMessage = (msg) => {
setMessages(prev => [...prev, { id: Date.now().toString(), role: 'user', parts: msg.parts }]);
setStatus('streaming');
setTimeout(() => {
setStatus('idle');
setMessages(prev => [...prev, { id: (Date.now() + 1).toString(), role: 'assistant', parts: [{ type: 'text', text: '你好，我是 DreamAI 助手！(这是一个模拟回复，因为环境未提供 window.ZeroAI)' }] }]);
}, 1500);
};
return { messages, setMessages, sendMessage, stop: () => { }, status };
};

const createLingGateway = window.ZeroAI?.createLingGateway || function () { return () => 'mock-model'; };

class LingTransport {
constructor(appId, modelId) {
this.ling = createLingGateway({ appId, baseURL: 'https://l-api.jd.com/toolkit/llm/proxy/v1' });
this.modelId = modelId;
}

async sendMessages({ messages, abortSignal }) {
if (!window.ZeroAI) return null;
const { streamText } = window.ZeroAI;
const result = streamText({
model: this.ling(this.modelId),
messages: messages.map(msg => ({
role: msg.role,
content: msg.parts.filter(p => p.type === 'text').map(p => p.text).join(''),
})),
abortSignal: abortSignal ?? undefined,
});
return result.toUIMessageStream();
}

async reconnectToStream() { return null; }
}

const mockKnowledgeBases = [
"前端研发规范",
"LangGraph 官方文档",
"业务系统使用手册",
"产品设计规范"
];

const mockHistory = [
{ title: "LangGraph Checkpointer: 状态持久化...", time: "今天 10:30" },
{ title: "LangGraph Checkpointer 面试回答指南", time: "昨天" },
{ title: "LangGraph State 深度解析与应用", time: "昨天" },
{ title: "LangGraph State: Centralized, Evolving...", time: "07-12" },
{ title: "AI 对话助手前端代码生成", time: "07-11" },
{ title: "LangGraph 中断本质：状态持久化与控...", time: "07-10" },
{ title: "AI 界面优化与组件建议", time: "07-10" }
];

function App() {
const [currentPage, setCurrentPage] = useState(window.__INITIAL_PAGE_KEY__ || 'index');

useEffect(() => {
const handlePageChange = () => {
const pageKey = document.querySelector('[data-page-key]')?.getAttribute('data-page-key');
if (pageKey && pageKey !== currentPage) {
setCurrentPage(pageKey);
}
};
handlePageChange();
}, []);

useEffect(() => {
window.__setCurrentPage = (pageKey) => {
if (pageKey) setCurrentPage(pageKey);
};
return () => { delete window.__setCurrentPage; };
}, []);

useEffect(() => {
const rootEl = document.querySelector('[data-page-key]');
if (!rootEl) return;
const observer = new MutationObserver(() => {
const newKey = rootEl.getAttribute('data-page-key');
if (newKey) setCurrentPage(newKey);
});
observer.observe(rootEl, { attributes: true, attributeFilter: ['data-page-key'] });
return () => observer.disconnect();
}, []);

const transport = React.useMemo(() => new LingTransport(window.APP_ID || '', 'Kimi-K2.5'), []);
const { messages, setMessages, sendMessage, stop, status } = useChat({ transport });
const [input, setInput] = useState('');
const [selectedKb, setSelectedKb] = useState(null);
const [showKbSelector, setShowKbSelector] = useState(false);
const [isSidebarOpen, setIsSidebarOpen] = useState(true);
const [activeHistoryIdx, setActiveHistoryIdx] = useState(null);
const [currentKb, setCurrentKb] = useState(null);
const isLoading = status === 'streaming' || status === 'submitted';
const messagesEndRef = useRef(null);

const scrollToBottom = () => {
messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
};

useEffect(() => {
scrollToBottom();
}, [messages]);

const handleSubmit = (e) => {
e.preventDefault();
if (!input.trim() || isLoading) return;
sendMessage({ role: 'user', parts: [{ type: 'text', text: input }] });
setInput('');
};

const handleHistoryClick = (idx, item) => {
setActiveHistoryIdx(idx);
if (typeof setMessages === 'function') {
setMessages([
{ id: `hist-${idx}-1`, role: 'user', parts: [{ type: 'text', text: `帮我回顾一下关于“${item.title}”的讨论` }] },
{ id: `hist-${idx}-2`, role: 'assistant', parts: [{ type: 'text', text: `好的，这是关于“${item.title}”的历史对话内容回顾...` }] }
]);
}
};

return (
<div className="w-full h-screen bg-white flex font-sans overflow-hidden text-gray-800" data-page-key={currentPage} data-ai-alt="AI助手Web版页面" data-ai-changelog-id="page-index" data-ai-changelog-title="AI问答助手Web版整体页面" data-ai-changelog-desc="左右分栏的Web端AI对话界面">
{/* 左侧边栏 */}
<aside className={`bg-[#F8F9FA] flex flex-col h-full shrink-0 transition-all duration-300 overflow-hidden ${isSidebarOpen ? 'w-[280px] border-r border-gray-100' : 'w-0 border-r-0'}`} data-ai-alt="左侧边栏" data-ai-changelog-id="sidebar-area" data-ai-changelog-title="历史记录侧边栏" data-ai-changelog-desc="包含新建对话、历史记录列表等导航功能" data-ai-clip="true">
<div className="w-[280px] h-full flex flex-col">
{/* Logo与收起按钮 */}
<div className="flex items-center px-[24px] h-[64px] shrink-0" data-ai-changelog-id="sidebar-toggle" data-ai-changelog-title="侧边栏收起开关" data-ai-changelog-desc="支持点击收起左侧边栏，增大主对话区域视野">
<button
className="flex items-center justify-center w-[16px] h-[32px] text-gray-600 hover:text-gray-800 transition-colors shrink-0"
onClick={() => setIsSidebarOpen(false)}
data-ai-alt="收起侧边栏"
>
<i className="fas fa-bars text-[16px]"></i>
</button>
<div
className="text-[18px] font-medium flex items-center cursor-pointer hover:opacity-80 transition-opacity ml-[12px] text-gray-800"
onClick={() => window.location.reload()}
data-ai-alt="应用Logo"
>
DreamAI
</div>
</div>
{/* 新建对话 */}
<div className="px-[12px] mb-[12px]" data-ai-alt="新建对话操作区">
<button
className="flex items-center bg-gray-200/50 hover:bg-gray-200 rounded-[20px] px-[12px] py-[10px] text-[14px] w-full transition-colors"
data-action="new-chat"
onClick={() => window.location.reload()}
data-ai-alt="新建对话按钮"
>
<i className="fas fa-edit text-[16px] text-gray-600 w-[16px] flex items-center justify-center shrink-0"></i>
<span className="ml-[12px] font-medium text-gray-800">发起新对话</span>
</button>
</div>
{/* 知识库入口 */}
<div className="px-[12px] mb-[16px]" data-ai-alt="知识库入口区" data-ai-changelog-id="sidebar-kb-nav" data-ai-changelog-title="知识库管理导航" data-ai-changelog-desc="侧边栏新增知识库入口，方便用户管理知识资产">
<button
className={`flex items-center rounded-[8px] px-[12px] py-[10px] text-[14px] w-full transition-colors ${currentPage === 'kbManager' || currentPage === 'kbDetail' ? 'bg-gray-200/50 text-gray-900 font-medium' : 'hover:bg-gray-200/50 text-gray-700'}`}
onClick={() => setCurrentPage('kbManager')}
data-action="go-kbManager"
data-ai-alt="知识库管理按钮"
>
<i className="fas fa-database text-[16px] text-gray-600 w-[16px] flex items-center justify-center shrink-0"></i>
<span className="ml-[12px] font-medium">知识库管理</span>
</button>
</div>
{/* 历史记录 */}
<div className="flex-1 overflow-y-auto px-[12px]" data-ai-alt="历史记录区域">
<div className="text-[12px] text-gray-500 pl-[12px] pr-[12px] py-[4px] mb-[4px] font-medium" data-ai-alt="近期记录标题">最近</div>
<ul className="flex flex-col gap-[2px]" data-ai-list="true">
{mockHistory.map((item, idx) => {
const isActive = activeHistoryIdx === idx;
return (
<li
key={idx}
onClick={() => handleHistoryClick(idx, item)}
className={`px-[16px] py-[10px] cursor-pointer transition-colors flex items-center justify-between rounded-full text-[13px] ${isActive ? 'bg-[#E8EAED] text-gray-900 font-medium' : 'hover:bg-gray-200/50 text-gray-700'}`}
data-ai-alt="历史记录项"
>
<span className="flex-1 truncate pr-[8px]">{item.title}</span>
<span className="text-[11px] text-gray-400 shrink-0">{item.time}</span>
</li>
);
})}
</ul>
</div>

          {/* 底部用户信息 */}
          <div className="px-[12px] pb-[16px] mt-auto shrink-0" data-ai-alt="用户信息区域" data-ai-changelog-id="sidebar-user-info" data-ai-changelog-title="底部用户信息" data-ai-changelog-desc="展示当前登录用户头像、名字及设置入口">
            <div className="flex items-center justify-between hover:bg-gray-200/50 cursor-pointer transition-colors rounded-[8px] px-[12px] py-[10px]">
              <div className="flex items-center gap-[12px]">
                <div className="w-[32px] h-[32px] rounded-full bg-indigo-500 text-white flex items-center justify-center text-[16px] font-medium shrink-0" data-ai-alt="用户头像">
                  J
                </div>
                <span className="text-[15px] text-gray-800 font-medium truncate" data-ai-alt="用户名">Jack</span>
              </div>
              <button className="text-gray-500 hover:text-gray-800 transition-colors flex items-center justify-center w-[28px] h-[28px] shrink-0" data-ai-alt="设置按钮">
                <i className="fas fa-cog text-[16px]"></i>
              </button>
            </div>
          </div>
        </div>
      </aside>

      {/* 右侧主区域 */}
      <main className="flex-1 flex flex-col relative h-full bg-white" data-ai-alt="主对话区" data-ai-clip="true">
        {/* 展开侧边栏按钮(当侧边栏收起时显示) */}
        {!isSidebarOpen && (
          <button
            className="absolute top-[16px] left-[16px] z-10 flex items-center justify-center w-[40px] h-[40px] rounded-full hover:bg-gray-100 text-gray-600 transition-colors"
            onClick={() => setIsSidebarOpen(true)}
            data-ai-alt="展开侧边栏"
          >
            <i className="fas fa-bars text-[16px]"></i>
          </button>
        )}

        {/* 顶部栏 */}
        <header className="h-[64px] flex justify-end items-center px-[24px] shrink-0" data-ai-alt="顶部操作栏">
        </header>

        {/* 对话内容区 */}
        <div className="flex-1 overflow-y-auto px-[20%] relative flex flex-col" data-ai-changelog-id="chat-message-list" data-ai-changelog-title="消息列表区域" data-ai-changelog-desc="展示用户与AI的对话内容，支持流式生成展示">
          {messages.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center -mt-[10%]" data-ai-alt="空状态引导">
              <h2 className="text-[36px] bg-clip-text text-transparent bg-gradient-to-r from-blue-500 to-purple-500 mb-[40px] font-medium" data-ai-alt="欢迎语">Jack, 稍微一等</h2>
              {/* 居中的大输入框 */}
              <div className="w-full max-w-[760px] relative" data-ai-alt="居中输入区" data-knowledge-citationid="2062092131721580545">
                {/* 知识库绑定提示 (居中大输入框上方) */}
                {selectedKb && (
                  <div className="absolute top-[-36px] left-[24px] bg-blue-50 text-blue-600 text-[12px] px-[12px] py-[4px] rounded-[12px] flex items-center gap-[6px] shadow-sm" data-ai-alt="已绑定知识库标签">
                    <i className="fas fa-book"></i>
                    已绑定: {selectedKb}
                    <i className="fas fa-times cursor-pointer hover:text-blue-800 ml-[4px]" onClick={() => setSelectedKb(null)} data-ai-alt="取消绑定"></i>
                  </div>
                )}
                <form onSubmit={handleSubmit} className="bg-white rounded-[24px] shadow-[0_2px_12px_rgba(0,0,0,0.08)] border border-gray-100 flex flex-col p-[16px] focus-within:shadow-[0_4px_16px_rgba(0,0,0,0.12)] transition-shadow" data-ai-alt="输入表单">
                  <textarea
                    value={input}
                    onChange={e => {
                      setInput(e.target.value);
                      e.target.style.height = 'auto';
                      e.target.style.height = e.target.scrollHeight + 'px';
                    }}
                    onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSubmit(e); } }}
                    placeholder="问问 Dream"
                    rows={1}
                    className="w-full bg-transparent outline-none focus:outline-none focus:ring-0 border-none text-[16px] text-gray-800 placeholder:text-gray-400 resize-none min-h-[48px] max-h-[160px] overflow-y-auto mb-[8px]"
                    disabled={isLoading}
                    data-ai-alt="多行大输入框"
                  />
                  <div className="flex items-center justify-between mt-[4px]" data-ai-alt="底部操作区">
                    <div className="relative flex items-center" data-ai-changelog-id="kb-binding-area" data-ai-changelog-title="知识库绑定与选择" data-ai-changelog-desc="支持对话过程中绑定知识库，实现基于指定知识库内容的增强问答">
                      <i
                        className={`fas fa-plus inline-flex items-center justify-center w-[32px] h-[32px] text-[20px] rounded-full hover:bg-gray-100 cursor-pointer transition-colors ${selectedKb ? 'text-blue-500' : 'text-gray-400 hover:text-blue-500'}`}
                        title="添加附件或绑定知识库"
                        onClick={() => setShowKbSelector(!showKbSelector)}
                        data-ai-alt="添加附件及知识库"
                        data-knowledge-citationid="kg://1963882999952154625/2071940487574765569/2071941880326688770/1#1782824414067706_ca493409fb9ff683_20260630210015_0"
                      ></i>
                      {showKbSelector && (
                        <div className="absolute bottom-[28px] left-[0px] w-[220px] bg-white border border-gray-100 shadow-[0_4px_20px_rgba(0,0,0,0.1)] rounded-[12px] py-[8px] z-10" data-ai-alt="知识库选择弹窗">
                          <div className="px-[16px] py-[6px] text-[12px] text-gray-500 font-medium">选择知识库</div>
                          {mockKnowledgeBases.map(kb => (
                            <div
                              key={kb}
                              className="px-[16px] py-[8px] hover:bg-gray-50 text-[13px] cursor-pointer flex items-center justify-between transition-colors"
                              onClick={() => { setSelectedKb(kb); setShowKbSelector(false); }}
                              data-ai-alt={`选择-${kb}`}
                            >
                              {kb}
                              {selectedKb === kb && <i className="fas fa-check text-blue-500 text-[12px]"></i>}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                    <div className="flex items-center gap-[16px] text-gray-500" data-ai-alt="右侧操作区">
                      <div className="flex items-center gap-[4px] cursor-pointer hover:bg-gray-100 px-[8px] py-[4px] rounded-[8px]" data-ai-alt="模型切换">
                        <span className="text-[14px]">Flash</span>
                        <i className="fas fa-chevron-down inline-flex items-center justify-center w-[12px] h-[12px] text-[12px]"></i>
                      </div>
                      <button type="submit" disabled={!input.trim() || isLoading} className="text-blue-500 hover:text-blue-600 disabled:text-gray-300 disabled:cursor-not-allowed" data-ai-alt="发送按钮">
                        {isLoading ? <i className="fas fa-stop inline-flex items-center justify-center w-[16px] h-[16px]"></i> : <i className="fas fa-paper-plane inline-flex items-center justify-center w-[18px] h-[18px] text-[18px]"></i>}
                      </button>
                    </div>
                  </div>
                </form>
              </div>
            </div>
          ) : (
            <div className="flex-1 py-[24px] flex flex-col gap-[32px]" data-ai-alt="流式对话容器" data-ai-list="true">
              {messages.map(msg => (
                <div key={msg.id} className="flex gap-[16px] text-[15px]" data-ai-alt="消息气泡项">
                  <div className="flex-1 leading-relaxed" data-ai-alt="消息内容区">
                    {msg.role === 'user' ? (
                      <div className="bg-[#F0F4F9] text-gray-800 p-[16px] rounded-[24px] rounded-tr-[4px] inline-block float-right max-w-[85%] shadow-sm" data-ai-alt="用户消息">
                        {msg.parts.filter(p => p.type === 'text').map(p => p.text).join('')}
                      </div>
                    ) : (
                      <div className="text-gray-800 mt-[4px]" data-ai-alt="AI回复">
                        {msg.parts.filter(p => p.type === 'text').map(p => p.text).join('')}
                      </div>
                    )}
                    <div className="clear-both"></div>
                  </div>
                </div>
              ))}
              {isLoading && status === 'streaming' && (
                <div className="flex gap-[16px]" data-ai-alt="AI正在输入动画">
                  <div className="flex-1 pt-[8px] flex gap-[4px]">
                    <div className="w-[6px] h-[6px] bg-blue-400 rounded-full animate-bounce"></div>
                    <div className="w-[6px] h-[6px] bg-blue-400 rounded-full animate-bounce" style={{ animationDelay: '0.2s' }}></div>
                    <div className="w-[6px] h-[6px] bg-blue-400 rounded-full animate-bounce" style={{ animationDelay: '0.4s' }}></div>
                  </div>
                </div>
              )}
              <div ref={messagesEndRef} className="h-[20px]" />
            </div>
          )}
        </div>

        {/* 底部悬浮输入框 (仅在有消息时显示在底部) */}
        {messages.length > 0 && (
          <div className="px-[20%] pb-[24px] pt-[12px] bg-gradient-to-t from-white via-white to-transparent shrink-0 relative" data-ai-changelog-id="chat-input-area" data-ai-changelog-title="底部悬浮输入区" data-ai-changelog-desc="固定在底部的对话输入框" data-knowledge-citationid="2062092131721580545">
            {/* 知识库绑定提示 (底部悬浮输入框上方) */}
            {selectedKb && (
              <div className="absolute top-[-24px] left-[20%] bg-blue-50 text-blue-600 text-[12px] px-[12px] py-[4px] rounded-[12px] flex items-center gap-[6px] shadow-sm ml-[24px]" data-ai-alt="已绑定知识库标签">
                <i className="fas fa-book"></i>
                已绑定: {selectedKb}
                <i className="fas fa-times cursor-pointer hover:text-blue-800 ml-[4px]" onClick={() => setSelectedKb(null)} data-ai-alt="取消绑定"></i>
              </div>
            )}
            <form onSubmit={handleSubmit} className="bg-[#F0F4F9] rounded-[24px] flex flex-col p-[16px] focus-within:bg-white focus-within:shadow-[0_2px_12px_rgba(0,0,0,0.08)] transition-all border border-transparent focus-within:border-gray-200" data-ai-alt="底部输入表单">
              <textarea
                value={input}
                onChange={e => {
                  setInput(e.target.value);
                  e.target.style.height = 'auto';
                  e.target.style.height = e.target.scrollHeight + 'px';
                }}
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSubmit(e); } }}
                placeholder="在此输入消息..."
                rows={1}
                className="w-full bg-transparent outline-none focus:outline-none focus:ring-0 border-none text-[15px] text-gray-800 placeholder:text-gray-500 resize-none min-h-[44px] max-h-[160px] overflow-y-auto mb-[8px]"
                disabled={isLoading}
                data-ai-alt="多行悬浮输入框"
              />
              <div className="flex items-center justify-between mt-[4px]" data-ai-alt="底部操作区">
                <div className="relative flex items-center" data-ai-changelog-id="kb-binding-area-floating" data-ai-changelog-title="知识库绑定与选择" data-ai-changelog-desc="支持对话过程中绑定知识库，实现基于指定知识库内容的增强问答">
                  <i
                    className={`fas fa-plus inline-flex items-center justify-center w-[32px] h-[32px] text-[20px] rounded-full hover:bg-gray-100 cursor-pointer transition-colors ${selectedKb ? 'text-blue-500' : 'text-gray-400 hover:text-blue-500'}`}
                    title="添加附件或绑定知识库"
                    onClick={() => setShowKbSelector(!showKbSelector)}
                    data-ai-alt="添加附件及知识库"
                  ></i>
                  {showKbSelector && (
                    <div className="absolute bottom-[28px] left-[0px] w-[220px] bg-white border border-gray-100 shadow-[0_4px_20px_rgba(0,0,0,0.1)] rounded-[12px] py-[8px] z-10" data-ai-alt="知识库选择弹窗">
                      <div className="px-[16px] py-[6px] text-[12px] text-gray-500 font-medium">选择知识库</div>
                      {mockKnowledgeBases.map(kb => (
                        <div
                          key={kb}
                          className="px-[16px] py-[8px] hover:bg-gray-50 text-[13px] cursor-pointer flex items-center justify-between transition-colors"
                          onClick={() => { setSelectedKb(kb); setShowKbSelector(false); }}
                          data-ai-alt={`选择-${kb}`}
                        >
                          {kb}
                          {selectedKb === kb && <i className="fas fa-check text-blue-500 text-[12px]"></i>}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
                <div className="flex items-center gap-[16px] text-gray-500" data-ai-alt="右侧操作区">
                  <div className="flex items-center gap-[4px] cursor-pointer hover:bg-gray-100 px-[8px] py-[4px] rounded-[8px]" data-ai-alt="模型切换">
                    <span className="text-[14px]">Flash</span>
                    <i className="fas fa-chevron-down inline-flex items-center justify-center w-[12px] h-[12px] text-[12px]"></i>
                  </div>
                  <button type="submit" disabled={!input.trim() || isLoading} className="text-blue-500 hover:text-blue-600 disabled:text-gray-300 disabled:cursor-not-allowed" data-ai-alt="发送按钮">
                    {isLoading ? <i className="fas fa-stop inline-flex items-center justify-center w-[16px] h-[16px]"></i> : <i className="fas fa-paper-plane inline-flex items-center justify-center w-[18px] h-[18px] text-[18px]"></i>}
                  </button>
                </div>
              </div>
            </form>
            <p className="text-center text-[12px] text-gray-400 mt-[12px]" data-ai-alt="免责声明" data-knowledge-citationid="2062092131721580545">
              AI 可能提供不准确的信息，请核对重要内容。
            </p>
          </div>
        )}

        {currentPage === 'kbManager' && (
          <div className="absolute inset-0 z-20 bg-white">
            <KbManager onGoDetail={(kb) => { setCurrentKb(kb); setCurrentPage('kbDetail'); }} />
          </div>
        )}
        {currentPage === 'kbDetail' && (
          <div className="absolute inset-0 z-20 bg-white">
            <KbDetail kb={currentKb} onBack={() => setCurrentPage('kbManager')} />
          </div>
        )}
      </main>
    </div>
);
}

export default App;
