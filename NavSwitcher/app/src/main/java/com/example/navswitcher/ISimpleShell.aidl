package com.example.navswitcher;

// 让 app 进程可以请求 “以 shell 身份” 在 UserService 里执行命令
interface ISimpleShell {
    int runCmd(String cmd); // 返回 exit code；>=0 表示进程退出码，负数表示异常
}
