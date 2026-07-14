import React, { useState } from 'react';

const mockKbs = [
    { id: 1, name: '前端研发规范', docCount: 12, updateTime: '2026-07-10' },
    { id: 2, name: 'LangGraph 官方文档', docCount: 5, updateTime: '2026-07-12' },
    { id: 3, name: '业务系统使用手册', docCount: 8, updateTime: '2026-07-13' },
    { id: 4, name: '产品设计规范', docCount: 3, updateTime: '2026-07-14' }
];

export default function KbManager({ onGoDetail }) {
    const [kbs, setKbs] = useState(mockKbs);
    return (
        <div className="w-full h-full flex flex-col bg-gray-50/50" data-ai-alt="知识库管理页面" data-ai-changelog-id="page-kbManager" data-ai-changelog-title="知识库管理页面整体" data-ai-changelog-desc="展示用户拥有的知识库卡片列表">
            <div className="h-[64px] px-[24px] flex items-center justify-between border-b border-gray-100 bg-white shrink-0" data-ai-alt="顶部操作栏">
                <h2 className="text-[18px] font-medium text-gray-800">知识库管理</h2>
                <button className="bg-blue-500 hover:bg-blue-600 text-white px-[16px] py-[8px] rounded-[8px] text-[14px] transition-colors flex items-center gap-[6px]" data-ai-alt="新增知识库" data-ai-changelog-id="kb-create-btn" data-ai-changelog-title="新增知识库按钮" data-ai-changelog-desc="点击可创建新的知识库">
                    <i className="fas fa-plus"></i> 新增知识库
                </button>
            </div>
            <div className="flex-1 p-[24px] overflow-y-auto" data-ai-alt="知识库列表区" data-ai-changelog-id="kb-list-area" data-ai-changelog-title="知识库卡片列表" data-ai-changelog-desc="以卡片形式平铺展示所有已建立的知识库">
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-[20px]" data-ai-list="true">
                    {kbs.map(kb => (
                        <div key={kb.id} className="bg-white border border-gray-100 rounded-[12px] p-[20px] hover:shadow-md transition-shadow cursor-pointer flex flex-col h-[140px]" onClick={() => onGoDetail(kb)} data-ai-alt="知识库卡片" data-action="go-kbDetail">
                            <div className="flex items-start justify-between mb-[12px]">
                                <div className="w-[40px] h-[40px] bg-blue-50 text-blue-500 rounded-[10px] flex items-center justify-center text-[18px]">
                                    <i className="fas fa-database"></i>
                                </div>
                                <button className="text-gray-400 hover:text-gray-600 w-[24px] h-[24px] flex items-center justify-center rounded-full hover:bg-gray-100 transition-colors" data-ai-alt="更多操作"><i className="fas fa-ellipsis-v"></i></button>
                            </div>
                            <h3 className="text-[15px] font-medium text-gray-800 mb-[12px] truncate leading-tight" data-ai-alt="知识库名称">{kb.name}</h3>
                            <div className="flex items-center text-[12px] text-gray-500 justify-between mt-auto">
                                <span data-ai-alt="文档数"><i className="far fa-file-alt mr-[6px]"></i>{kb.docCount} 份文档</span>
                                <span data-ai-alt="更新时间">{kb.updateTime}</span>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}
