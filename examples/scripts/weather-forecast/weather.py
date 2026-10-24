#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
查一个城市未来几天的天气预报。

只依赖标准库：Open-Meteo 的地理编码与预报接口都是公开的，不需要 key。
这一点不是巧合——脚本层的子进程只拿得到 PATH（见 SandboxPolicy），
凡是依赖 API key 的能力在脚本层里都跑不通，那个结论写在 ADR 里。
"""

import json
import sys
import urllib.parse
import urllib.request

GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST = "https://api.open-meteo.com/v1/forecast"
TIMEOUT = 15


def fetch(url):
    with urllib.request.urlopen(url, timeout=TIMEOUT) as response:
        return json.loads(response.read().decode("utf-8"))


def locate(city):
    query = urllib.parse.urlencode({"name": city, "count": 1, "language": "zh"})
    places = fetch("%s?%s" % (GEOCODING, query)).get("results") or []
    if not places:
        return None
    place = places[0]
    return {
        "name": place.get("name"),
        "country": place.get("country") or place.get("country_code") or "",
        "latitude": place["latitude"],
        "longitude": place["longitude"],
    }


def forecast(place, days):
    query = urllib.parse.urlencode({
        "latitude": place["latitude"],
        "longitude": place["longitude"],
        "daily": "temperature_2m_max,temperature_2m_min,precipitation_probability_max",
        "forecast_days": days,
        "timezone": "auto",
    })
    return fetch("%s?%s" % (FORECAST, query)).get("daily") or {}


def main():
    if len(sys.argv) < 2:
        print("用法：weather-forecast <城市名>，例如：weather-forecast 深圳")
        return 2
    city = " ".join(sys.argv[1:])
    try:
        days = 3
        place = locate(city)
        if place is None:
            print("没找到这个城市：%s。换一个更常见的写法试试（例如“深圳市”或 Shenzhen）。" % city)
            return 1
        daily = forecast(place, days)
    except Exception as failure:  # noqa: BLE001
        print("查天气预报失败：%s" % failure)
        return 1

    print("%s（%s）未来 %d 天：" % (place["name"], place["country"], days))
    for index, date in enumerate(daily.get("time", [])):
        print("  %s  最高 %.0f℃  最低 %.0f℃  降水概率 %s%%" % (
            date,
            daily["temperature_2m_max"][index],
            daily["temperature_2m_min"][index],
            daily["precipitation_probability_max"][index] or 0,
        ))
    return 0


if __name__ == "__main__":
    sys.exit(main())
