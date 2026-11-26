#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
天气与空气质量（open-meteo）。

三个工具，一个数据源。选它是因为它同时提供**地理编码**（城市名 → 经纬度）、预报、空气质量，
而且都不需要 key——少一个 key 就少一处部署时的失败点。

调用链是固定两步：先把城市名换成经纬度，再拿经纬度查天气。模型只给城市名，
经纬度这件事不该让它知道：多一个必填参数，它就多一次填错的机会。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from http_json import get_json, lines, text_of  # noqa: E402
from mcp_stdio import Tool, serve  # noqa: E402

GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST = "https://api.open-meteo.com/v1/forecast"
AIR = "https://air-quality-api.open-meteo.com/v1/air-quality"

# WMO 天气代码 → 中文。官方有 28 个码，这里按"用户会问到的"合并：61–65 都是雨，
# 分成"小雨/中雨/大雨"对回答没有增益，反而让模型在措辞上纠结。
WEATHER_CODES = {
    0: "晴", 1: "晴间多云", 2: "多云", 3: "阴",
    45: "雾", 48: "雾凇",
    51: "毛毛雨", 53: "毛毛雨", 55: "毛毛雨", 56: "冻毛毛雨", 57: "冻毛毛雨",
    61: "小雨", 63: "中雨", 65: "大雨", 66: "冻雨", 67: "冻雨",
    71: "小雪", 73: "中雪", 75: "大雪", 77: "雪粒",
    80: "阵雨", 81: "阵雨", 82: "强阵雨", 85: "阵雪", 86: "阵雪",
    95: "雷阵雨", 96: "雷阵雨伴冰雹", 99: "雷阵雨伴冰雹",
}


def locate(city):
    """
    城市名 → (地点, None) 或 (None, 原因)。

    带 language=zh：返回的中文名（"東京""日本"）比英文名更容易和用户的话对上，
    排障时也一眼看得出选错了哪个地方。
    """
    data, error = get_json(GEOCODE, {"name": city, "count": 1, "language": "zh"})
    if error:
        return None, error
    results = data.get("results") or []
    if not results:
        return None, "找不到这个地方：%s。换个写法试试（例如「Tokyo」而不是「东京都涩谷区」）" % city
    return results[0], None


def where(place):
    parts = [place.get("name"), place.get("admin1"), place.get("country")]
    return "，".join(part for part in parts if part)


def weather_now(arguments):
    place, error = locate(arguments["city"])
    if error:
        return error
    data, error = get_json(FORECAST, {
        "latitude": place["latitude"], "longitude": place["longitude"],
        "current": "temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code",
        "timezone": "auto",
    })
    if error:
        return error
    current = data.get("current") or {}
    code = current.get("weather_code")
    return lines([
        "%s（%s）" % (where(place), data.get("timezone", "")),
        "天气：%s" % WEATHER_CODES.get(code, "未知（代码 %s）" % code),
        "气温：%s°C（体感 %s°C）" % (text_of(current.get("temperature_2m")),
                                  text_of(current.get("apparent_temperature"))),
        "湿度：%s%%" % text_of(current.get("relative_humidity_2m")),
        "风速：%s km/h" % text_of(current.get("wind_speed_10m")),
        "观测时间：%s" % text_of(current.get("time")),
    ])


def weather_forecast(arguments):
    place, error = locate(arguments["city"])
    if error:
        return error
    days = int(arguments.get("days", 7))
    days = max(1, min(days, 16))
    data, error = get_json(FORECAST, {
        "latitude": place["latitude"], "longitude": place["longitude"],
        "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
        "timezone": "auto", "forecast_days": days,
    })
    if error:
        return error
    daily = data.get("daily") or {}
    dates = daily.get("time") or []
    rows = ["%s 未来 %d 天（%s）" % (where(place), len(dates), data.get("timezone", ""))]
    for index, date in enumerate(dates):
        code = (daily.get("weather_code") or [None] * len(dates))[index]
        rows.append("%s  %s  %s°C ~ %s°C  降水概率 %s%%" % (
            date,
            WEATHER_CODES.get(code, "未知"),
            text_of((daily.get("temperature_2m_min") or [None] * len(dates))[index]),
            text_of((daily.get("temperature_2m_max") or [None] * len(dates))[index]),
            text_of((daily.get("precipitation_probability_max") or [None] * len(dates))[index]),
        ))
    return lines(rows)


def air_quality(arguments):
    place, error = locate(arguments["city"])
    if error:
        return error
    data, error = get_json(AIR, {
        "latitude": place["latitude"], "longitude": place["longitude"],
        "current": "pm2_5,pm10,european_aqi",
    })
    if error:
        return error
    current = data.get("current") or {}
    aqi = current.get("european_aqi")
    return lines([
        "%s 的空气质量" % where(place),
        "PM2.5：%s μg/m³" % text_of(current.get("pm2_5")),
        "PM10：%s μg/m³" % text_of(current.get("pm10")),
        "欧洲 AQI：%s（%s）" % (text_of(aqi), aqi_level(aqi)),
        "观测时间：%s" % text_of(current.get("time")),
    ])


def aqi_level(aqi):
    """欧洲 AQI 的分档。判断放在这里而不是让模型猜，同一份数据才不会有两种解读。"""
    if not isinstance(aqi, (int, float)):
        return "未知"
    if aqi <= 20:
        return "很好"
    if aqi <= 40:
        return "良"
    if aqi <= 60:
        return "中等"
    if aqi <= 80:
        return "差"
    if aqi <= 100:
        return "很差"
    return "极差"


if __name__ == "__main__":
    serve("claw4j-weather", [
        Tool("weather_now",
             "查某个城市现在的天气：天气状况、气温、体感温度、湿度、风速。用户问「今天热不热」「要不要带伞」时用这个。",
             {"city": {"type": "string", "description": "城市名，中英文都行，例如 北京 / Tokyo"}},
             weather_now, ["city"]),
        Tool("weather_forecast",
             "查某个城市未来几天的天气预报：每天的最高最低温与降水概率。",
             {"city": {"type": "string", "description": "城市名，中英文都行"},
              "days": {"type": "integer", "description": "往后看几天，默认 7，最多 16"}},
             weather_forecast, ["city"]),
        Tool("air_quality",
             "查某个城市的空气质量：PM2.5、PM10、AQI 指数与等级。",
             {"city": {"type": "string", "description": "城市名，中英文都行"}},
             air_quality, ["city"]),
    ])
