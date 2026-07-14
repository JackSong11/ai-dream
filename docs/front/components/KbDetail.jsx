import React, { useState } from 'react';

const mockFiles = [
    { id: 1, name: 'React编码规范.pdf', size: '2.4 MB', time: '2026-07-10 14:00', status: '已解析' },
    { id: 2, name: 'Tailwind使用指南.docx', size: '1.1 MB', time: '2026-07-11 09:30', status: '已解析' },
    { id: 3, name: '前端性能优化.md', size: '45 KB', time: '2026-07-14 10:15', status: '解析中' }
];

export default function KbDetail({ kb, onBack }) {
    const [files, setFiles] = useState(mockFiles);
    return (
        <div className="w-full h-full flex flex-col bg-gray-50/50" data-ai-alt="知识库详情页面" data-ai-changelog-id="page-kbDetail" data-ai-changelog-title="知识库详情页面整体" data-ai-changelog-desc="展示选中知识库内部的文件列表与状态">
            <div className="h-[64px] px-[24px] flex items-center justify-between border-b border-gray-100 bg-white shrink-0" data-ai-alt="顶部操作栏">
                <div className="flex items-center gap-[12px]">
                    <button className="text-gray-500 hover:text-gray-800 flex items-center justify-center w-[32px] h-[32px] rounded-full hover:bg-gray-100 transition-colors" onClick={onBack} data-action="go-kbManager" data-ai-alt="返回上一页">
                        <i className="fas fa-arrow-left"></i>
                    </button>
                    <h2 className="text-[18px] font-medium text-gray-800">{kb?.name || '知识库详情'}</h2>
                </div>
                <button className="bg-blue-500 hover:bg-blue-600 text-white px-[16px] py-[8px] rounded-[8px] text-[14px] transition-colors flex items-center gap-[6px]" data-ai-alt="上传文件" data-ai-changelog-id="kb-file-upload" data-ai-changelog-title="上传文件按钮" data-ai-changelog-desc="支持本地选择文件上传并解析加入知识库">
                    <i className="fas fa-upload"></i> 上传文件
                </button>
            </div>
            <div className="flex-1 p-[24px] overflow-y-auto" data-ai-alt="文件列表区" data-ai-changelog-id="kb-file-list" data-ai-changelog-title="知识库文件列表" data-ai-changelog-desc="以表格形式展示当前知识库已有的所有文件及解析状态">
                <div className="bg-white border border-gray-100 rounded-[12px] overflow-hidden shadow-sm">
                    <table className="w-full text-left border-collapse">
                        <thead>
                        <tr className="bg-gray-50 border-b border-gray-100 text-[13px] text-gray-500">
                            <th className="px-[24px] py-[12px] font-medium">文件名</th>
                            <th className="px-[24px] py-[12px] font-medium w-[120px]">大小</th>
                            <th className="px-[24px] py-[12px] font-medium w-[180px]">上传时间</th>
                            <th className="px-[24px] py-[12px] font-medium w-[100px]">状态</th>
                            <th className="px-[24px] py-[12px] font-medium w-[80px]">操作</th>
                        </tr>
                        </thead>
                        <tbody data-ai-list="true">
                        {files.map(f => (
                            <tr key={f.id} className="border-b border-gray-50 hover:bg-gray-50/50 transition-colors text-[14px]" data-ai-alt="文件列表项">
                                <td className="px-[24px] py-[16px] flex items-center gap-[12px]">
                                    <i className={`fas fa-file-${f.name.endsWith('pdf') ? 'pdf text-red-500' : f.name.endsWith('docx') ? 'word text-blue-500' : 'alt text-gray-400'} text-[20px]`}></i>
                                    <span className="text-gray-800 font-medium">{f.name}</span>
                                </td>
                                <td className="px-[24px] py-[16px] text-gray-500">{f.size}</td>
                                <td className="px-[24px] py-[16px] text-gray-500">{f.time}</td>
                                <td className="px-[24px] py-[16px]">
                    <span className={`inline-flex items-center justify-center px-[10px] py-[4px] rounded-full text-[12px] font-medium ${f.status === '已解析' ? 'bg-green-50 text-green-600' : 'bg-blue-50 text-blue-600'}`}>
                      {f.status === '解析中' && <i className="fas fa-spinner fa-spin mr-[4px]"></i>}
                        {f.status}
                    </span>
                                </td>
                                <td className="px-[24px] py-[16px]">
                                    <button className="text-gray-400 hover:text-red-500 transition-colors w-[28px] h-[28px] flex items-center justify-center rounded-full hover:bg-red-50" data-ai-alt="删除文件"><i className="fas fa-trash-alt"></i></button>
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    );
}
