# Vendored wxauto 3.9（微信 3.9.8.15 适配版）

本目录是从 <https://github.com/cluic/wxauto> 的 `WeChat3.9.8` 分支原样拷贝的
Python 源码（含其自带的 `uiautomation.py`），协议为 **MIT License**，版权归原作者，
见本目录 [LICENSE](LICENSE)。

为什么 vendored：

- `wxauto` 不在 PyPI 上发布（老版本已被下架），且该仓库没有 `setup.py`，
  无法用 `pip install <git-url>` 直接安装；
- 直接放进项目里，`python/wechat_bridge_3x.py` 可以 `from wxauto import WeChat`
  开箱即用，不需要配置 `PYTHONPATH`。

注意事项：

- 该分支只适配**微信 PC 客户端 3.9.8.15**，其他 3.9.x 小版本可能因 UI 变化而失效；
- 微信升级到 4.x 后本目录代码不可用，请改用 `wxautox4`（见 `python/wechat_bridge_4x.py`）；
- 需要更新时，直接重新拷贝 GitHub 分支内容即可。
