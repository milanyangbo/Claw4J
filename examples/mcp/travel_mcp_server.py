#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
出行相关的四件事：时差、现在几点、日出日落、两地距离。

**全部在本地算，一次网络都不发。** 原因是票 26 实测过：Nominatim 超时、OSRM 断连，
而时区与日出日落本来就有闭式解——一个助理回答"伦敦现在几点""明天几点天黑"
不该依赖任何第三方服务还活着。

城市表内置 40 个常用城市（经纬度 + IANA 时区）。查不到就明说"不在表里"，
按经纬度猜时区是另一种猜法，猜错了比查不到更糟。

`sun_times` 用的是日出方程（NOAA 的简化式），误差在分钟级——够回答"要不要带伞"，
不够拿去校天文台。极昼极夜会如实说是极昼/极夜，而不是给一个假的时刻。
"""

import math
import os
import sys
from datetime import date, datetime, time, timezone
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from http_json import lines  # noqa: E402
from mcp_stdio import Tool, serve  # noqa: E402

# (别名们, 纬度, 经度, IANA 时区)
CITIES = [
    (("北京", "beijing"), 39.9042, 116.4074, "Asia/Shanghai"),
    (("上海", "shanghai"), 31.2304, 121.4737, "Asia/Shanghai"),
    (("广州", "guangzhou"), 23.1291, 113.2644, "Asia/Shanghai"),
    (("深圳", "shenzhen"), 22.5431, 114.0579, "Asia/Shanghai"),
    (("成都", "chengdu"), 30.5728, 104.0668, "Asia/Shanghai"),
    (("杭州", "hangzhou"), 30.2741, 120.1551, "Asia/Shanghai"),
    (("西安", "xian"), 34.3416, 108.9398, "Asia/Shanghai"),
    (("香港", "hongkong", "hong kong"), 22.3193, 114.1694, "Asia/Hong_Kong"),
    (("澳门", "macau", "macao"), 22.1987, 113.5439, "Asia/Macau"),
    (("台北", "taipei"), 25.0330, 121.5654, "Asia/Taipei"),
    (("东京", "tokyo"), 35.6762, 139.6503, "Asia/Tokyo"),
    (("大阪", "osaka"), 34.6937, 135.5023, "Asia/Tokyo"),
    (("首尔", "seoul"), 37.5665, 126.9780, "Asia/Seoul"),
    (("新加坡", "singapore"), 1.3521, 103.8198, "Asia/Singapore"),
    (("曼谷", "bangkok"), 13.7563, 100.5018, "Asia/Bangkok"),
    (("吉隆坡", "kualalumpur", "kuala lumpur"), 3.1390, 101.6869, "Asia/Kuala_Lumpur"),
    (("雅加达", "jakarta"), -6.2088, 106.8456, "Asia/Jakarta"),
    (("孟买", "mumbai"), 19.0760, 72.8777, "Asia/Kolkata"),
    (("迪拜", "dubai"), 25.2048, 55.2708, "Asia/Dubai"),
    (("利雅得", "riyadh"), 24.7136, 46.6753, "Asia/Riyadh"),
    (("伊斯坦布尔", "istanbul"), 41.0082, 28.9784, "Europe/Istanbul"),
    (("莫斯科", "moscow"), 55.7558, 37.6173, "Europe/Moscow"),
    (("伦敦", "london"), 51.5074, -0.1278, "Europe/London"),
    (("巴黎", "paris"), 48.8566, 2.3522, "Europe/Paris"),
    (("柏林", "berlin"), 52.5200, 13.4050, "Europe/Berlin"),
    (("马德里", "madrid"), 40.4168, -3.7038, "Europe/Madrid"),
    (("罗马", "rome", "roma"), 41.9028, 12.4964, "Europe/Rome"),
    (("阿姆斯特丹", "amsterdam"), 52.3676, 4.9041, "Europe/Amsterdam"),
    (("苏黎世", "zurich"), 47.3769, 8.5417, "Europe/Zurich"),
    (("开罗", "cairo"), 30.0444, 31.2357, "Africa/Cairo"),
    (("开普敦", "capetown", "cape town"), -33.9249, 18.4241, "Africa/Johannesburg"),
    (("内罗毕", "nairobi"), -1.2921, 36.8219, "Africa/Nairobi"),
    (("纽约", "newyork", "new york"), 40.7128, -74.0060, "America/New_York"),
    (("芝加哥", "chicago"), 41.8781, -87.6298, "America/Chicago"),
    (("洛杉矶", "losangeles", "los angeles", "la"), 34.0522, -118.2437, "America/Los_Angeles"),
    (("旧金山", "sanfrancisco", "san francisco"), 37.7749, -122.4194, "America/Los_Angeles"),
    (("西雅图", "seattle"), 47.6062, -122.3321, "America/Los_Angeles"),
    (("多伦多", "toronto"), 43.6532, -79.3832, "America/Toronto"),
    (("温哥华", "vancouver"), 49.2827, -123.1207, "America/Vancouver"),
    (("墨西哥城", "mexicocity", "mexico city"), 19.4326, -99.1332, "America/Mexico_City"),
    (("圣保罗", "saopaulo", "sao paulo"), -23.5505, -46.6333, "America/Sao_Paulo"),
    (("布宜诺斯艾利斯", "buenosaires", "buenos aires"), -34.6037, -58.3816, "America/Argentina/Buenos_Aires"),
    (("悉尼", "sydney"), -33.8688, 151.2093, "Australia/Sydney"),
    (("墨尔本", "melbourne"), -37.8136, 144.9631, "Australia/Melbourne"),
    (("奥克兰", "auckland"), -36.8485, 174.7633, "Pacific/Auckland"),
]

EARTH_RADIUS_KM = 6371.0088


def resolve(city):
    """按别名找城市。返回 (显示名, 纬度, 经度, 时区) 或 None。"""
    key = (city or "").strip().lower()
    if not key:
        return None
    for names, lat, lon, zone in CITIES:
        if key in (name.lower() for name in names):
            return names[0], lat, lon, zone
    return None


def city_or_error(city, field):
    """查得到就给 (城市, None)，查不到就给 (None, 一句为什么)。

    不抛异常：城市名打错是用户输入的问题，不是工具坏了。抛出去会被 MCP 层包成
    "调用失败"，模型看到的是一句错报，而它本来只要换个名字就能继续。
    """
    found = resolve(city)
    if found is None:
        return None, "%s「%s」不在城市表里（内置 40 个常用城市）。换个城市名，" \
                      "或者直接给我经纬度，我按经纬度算。" % (field, city)
    return found, None


def offset_text(moment):
    total = int(moment.utcoffset().total_seconds() // 60)
    sign = "+" if total >= 0 else "-"
    return "UTC%s%02d:%02d" % (sign, abs(total) // 60, abs(total) % 60)


def parse_moment(text, zone):
    """把「2026-11-27 09:00」「2026-11-27T09:00」都解成一个带时区的时刻。只给钟点时用今天。"""
    cleaned = (text or "").strip().replace("/", "-")
    if not cleaned:
        return None, "要给一个时刻，例如「2026-11-27 09:00」。"
    for pattern in ("%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(cleaned, pattern).replace(tzinfo=ZoneInfo(zone)), None
        except ValueError:
            continue
    for pattern in ("%H:%M", "%H:%M:%S"):
        try:
            clock = datetime.strptime(cleaned, pattern).time()
            return datetime.combine(date.today(), clock).replace(tzinfo=ZoneInfo(zone)), None
        except ValueError:
            continue
    return None, "时刻看不懂：%s。用「2026-11-27 09:00」这种写法。" % cleaned


def timezone_convert(arguments):
    source, error = city_or_error(arguments["from_city"], "出发城市")
    if error:
        return error
    target, error = city_or_error(arguments["to_city"], "目标城市")
    if error:
        return error
    moment, error = parse_moment(arguments["when"], source[3])
    if error:
        return error
    converted = moment.astimezone(ZoneInfo(target[3]))
    delta = converted.utcoffset() - moment.utcoffset()
    hours = delta.total_seconds() / 3600.0
    return lines([
        "%s：%s（%s）" % (source[0], moment.strftime("%Y-%m-%d %H:%M"), offset_text(moment)),
        "%s：%s（%s）" % (target[0], converted.strftime("%Y-%m-%d %H:%M"), offset_text(converted)),
        "时差：%s 比 %s %s %.1f 小时" % (target[0], source[0],
                                       "快" if hours >= 0 else "慢", abs(hours)),
    ])


def world_clock(arguments):
    wanted = arguments.get("cities") or "北京"
    if isinstance(wanted, str):
        wanted = [name.strip() for name in wanted.split(",") if name.strip()]
    if arguments.get("at"):
        moment, error = parse_moment(arguments["at"], "UTC")
        if error:
            return error
    else:
        moment = datetime.now(timezone.utc)
    rows = ["基准时刻：%s UTC" % moment.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M")]
    for city in wanted:
        found = resolve(city)
        if found is None:
            rows.append("- %s：不在城市表里" % city)
            continue
        local = moment.astimezone(ZoneInfo(found[3]))
        rows.append("- %s：%s（%s，%s）" % (
            found[0], local.strftime("%m-%d %H:%M"), offset_text(local),
            "今天" if local.date() == moment.date() else local.strftime("%m-%d")))
    return lines(rows)


def julian_day(moment):
    return moment.timestamp() / 86400.0 + 2440587.5


def from_julian(value):
    return datetime.fromtimestamp((value - 2440587.5) * 86400.0, tz=timezone.utc)


def sun_of(lat, lon, day, zone):
    """日出方程（NOAA 简化式）。返回 (日出, 日落) 或 (None, 原因)。"""
    local_noon = datetime.combine(day, time(12, 0)).replace(tzinfo=ZoneInfo(zone))
    n = round(julian_day(local_noon.astimezone(timezone.utc)) - 2451545.0 + 0.0008)
    mean_solar_time = n - lon / 360.0
    anomaly = (357.5291 + 0.98560028 * mean_solar_time) % 360
    center = (1.9148 * math.sin(math.radians(anomaly))
              + 0.0200 * math.sin(math.radians(2 * anomaly))
              + 0.0003 * math.sin(math.radians(3 * anomaly)))
    ecliptic = (anomaly + center + 180 + 102.9372) % 360
    transit = (2451545.0 + mean_solar_time
               + 0.0053 * math.sin(math.radians(anomaly))
               - 0.0069 * math.sin(math.radians(2 * ecliptic)))
    declination = math.asin(math.sin(math.radians(ecliptic)) * math.sin(math.radians(23.4397)))
    denominator = math.cos(math.radians(lat)) * math.cos(declination)
    if abs(denominator) < 1e-9:
        return None, "极地：这一天太阳不升不落"
    cos_hour_angle = ((math.sin(math.radians(-0.833))
                       - math.sin(math.radians(lat)) * math.sin(declination)) / denominator)
    if cos_hour_angle > 1:
        return None, "极夜：这一天太阳整天不升"
    if cos_hour_angle < -1:
        return None, "极昼：这一天太阳整天不落"
    hour_angle = math.degrees(math.acos(cos_hour_angle))
    rise = from_julian(transit - hour_angle / 360.0).astimezone(ZoneInfo(zone))
    setting = from_julian(transit + hour_angle / 360.0).astimezone(ZoneInfo(zone))
    return (rise, setting), None


def sun_times(arguments):
    found, error = city_or_error(arguments["city"], "城市")
    if error:
        return error
    raw = (arguments.get("date") or "").strip()
    try:
        day = datetime.strptime(raw, "%Y-%m-%d").date() if raw else date.today()
    except ValueError:
        return "日期看不懂：%s。用 2026-06-21 这种写法。" % raw
    (result, reason) = sun_of(found[1], found[2], day, found[3])
    if result is None:
        return "%s %s：%s。" % (found[0], day.isoformat(), reason)
    rise, setting = result
    span = (setting - rise).total_seconds() / 3600.0
    return lines([
        "%s %s" % (found[0], day.isoformat()),
        "日出：%s    日落：%s（当地时间，%s）" % (
            rise.strftime("%H:%M"), setting.strftime("%H:%M"), offset_text(rise)),
        "昼长：%d 小时 %d 分" % (int(span), int(round((span - int(span)) * 60))),
        "（日出方程估算，误差分钟级；不是天文台数据）",
    ])


def city_distance(arguments):
    start, error = city_or_error(arguments["from_city"], "起点城市")
    if error:
        return error
    end, error = city_or_error(arguments["to_city"], "终点城市")
    if error:
        return error
    lat1, lon1 = math.radians(start[1]), math.radians(start[2])
    lat2, lon2 = math.radians(end[1]), math.radians(end[2])
    delta_lat = lat2 - lat1
    delta_lon = lon2 - lon1
    a = (math.sin(delta_lat / 2) ** 2
         + math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2) ** 2)
    distance = 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(a))
    return lines([
        "%s → %s：直线距离约 %.0f 公里" % (start[0], end[0], distance),
        "（大圆距离，不是航线也不是里程；实际飞行通常还要多 5%–10%）",
    ])


if __name__ == "__main__":
    serve("claw4j-travel", [
        Tool("timezone_convert",
             "把一个具体时刻从一个城市换算到另一个城市，并给出时差。用户问「北京下午三点纽约几点」时用。",
             {"when": {"type": "string", "description": "出发地的时刻，例如 2026-11-27 09:00"},
              "from_city": {"type": "string", "description": "出发城市，例如 北京"},
              "to_city": {"type": "string", "description": "目标城市，例如 纽约"}},
             timezone_convert, ["when", "from_city", "to_city"]),
        Tool("world_clock",
             "同时给出几个城市现在各是几点。用户问「现在伦敦几点」时用。",
             {"cities": {"type": "string", "description": "逗号分隔的城市，例如「伦敦,纽约,东京」"},
              "at": {"type": "string", "description": "可选：换算到这个时刻，不写就是现在"}},
             world_clock),
        Tool("sun_times",
             "查某地某天的日出、日落与昼长。用户问「几点天黑」时用。",
             {"city": {"type": "string", "description": "城市名，例如 上海"},
              "date": {"type": "string", "description": "日期，例如 2026-06-21，不写就是今天"}},
             sun_times, ["city"]),
        Tool("city_distance",
             "算两个城市之间的直线距离（公里）。用户问「两地多远」时用。",
             {"from_city": {"type": "string", "description": "起点城市"},
              "to_city": {"type": "string", "description": "终点城市"}},
             city_distance, ["from_city", "to_city"]),
    ])
