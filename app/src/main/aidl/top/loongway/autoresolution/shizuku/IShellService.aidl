/**
 * 通用 Shell 命令执行接口。App 进程 → Shizuku 高权限进程。
 */
package top.loongway.autoresolution.shizuku;

interface IShellService {
    String exec(String command) = 1;
    void destroy() = 16777114; // Shizuku reserved
}
