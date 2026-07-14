#!/system/bin/sh

ui_print "- 正在安装 ComforTroll Guard"
ui_print "- 后台白名单与巡测心跳监测将在重启后生效"
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
