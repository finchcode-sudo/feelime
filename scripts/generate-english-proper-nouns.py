#!/usr/bin/env python3
"""生成 app/src/main/assets/english-proper-nouns.txt（英文补全用的专有名词词表）。

格式：一行一个词形；与高频词表（english-words.txt）同 key 的词默认不覆盖
（人名里的 Mark/White/King 本身是常用词，保持小写形态）；品牌/公司/地名
类行尾加 TAB+`!` 表示「覆盖小写词面」（china → China、apple → Apple）。
用法：generate-english-proper-nouns.py   （幂等，整文件重写）
"""
import pathlib

OUT = pathlib.Path("app/src/main/assets/english-proper-nouns.txt")

TECH = """GitHub GitLab Bitbucket Android iPhone iPad iPod MacBook macOS iOS watchOS iPadOS Windows Linux Ubuntu Debian Fedora
OnePlus Xiaomi Huawei Samsung Pixel Galaxy Oppo Vivo Realme Honor Redmi Nokia Motorola Sony Nintendo PlayStation Xbox Steam
ChatGPT OpenAI Claude Anthropic Gemini Copilot DeepSeek Midjourney Llama Mistral Qwen Kimi Doubao Perplexity Grok
TikTok WeChat WhatsApp Telegram Discord Slack Zoom Skype Notion Obsidian Figma Photoshop Illustrator Premiere Blender Unity Unreal
Docker Kubernetes Terraform Jenkins PostgreSQL MongoDB MySQL SQLite Redis Elasticsearch TensorFlow PyTorch NumPy Pandas Jupyter
Python JavaScript TypeScript Kotlin Swift Rust Golang Angular Svelte Webpack Vite Tailwind Bootstrap jQuery WordPress Shopify
Cloudflare Vercel Netlify Heroku Firebase Supabase Termux Magisk LSPosed Xposed ColorOS MIUI HyperOS HarmonyOS OxygenOS
Chromium Firefox Safari Chrome AirPods AirDrop FaceTime iCloud iMessage Siri Alexa Kindle Raspberry Arduino Nvidia Intel
Qualcomm Snapdragon Dimensity MediaTek Tesla SpaceX Starlink Gboard Rime Hunspell Gradle Maven""".split()

COMPANY = """Google Amazon Apple Microsoft Meta Facebook Instagram Twitter YouTube Netflix Spotify Reddit Pinterest LinkedIn Snapchat
Twitch Bilibili Weibo Taobao Alibaba Tencent Baidu ByteDance Douyin Xiaohongshu Zhihu Alipay PayPal Stripe Uber Airbnb Booking
eBay Walmart Costco IKEA Starbucks McDonald KFC Nike Adidas Disney HBO Wikipedia StackOverflow Quora Medium Substack Patreon
Dropbox OneDrive Pornhub Oracle IBM Cisco Adobe Salesforce AMD Toyota Honda BMW Mercedes Audi Volkswagen Ford Boeing Airbus
Visa Mastercard Coinbase Binance Bitcoin Ethereum Dogecoin Huawei Lenovo Dell Asus Acer Logitech Razer Canon Nikon Panasonic
Samsung Hyundai Pfizer Moderna Meituan Pinduoduo Didi Xiaomi Mihoyo Tencent""".split()

PLACE = """China Japan Korea India Russia America Britain England France Germany Italy Spain Portugal Canada Australia Brazil Mexico
Argentina Egypt Turkey Thailand Vietnam Singapore Malaysia Indonesia Philippines Pakistan Ukraine Poland Sweden Norway Finland
Denmark Netherlands Belgium Switzerland Austria Greece Ireland Scotland Israel Iran Iraq Taiwan Hongkong Macau Mongolia Nepal
Africa Europe Asia Antarctica Beijing Shanghai Shenzhen Guangzhou Hangzhou Chengdu Chongqing Wuhan Nanjing Tianjin Suzhou
Qingdao Xiamen Tokyo Osaka Kyoto Seoul Bangkok Paris London Berlin Madrid Rome Moscow Sydney Melbourne Toronto Vancouver Dubai
Mumbai Delhi Istanbul Amsterdam Vienna Zurich Geneva Prague Budapest Barcelona Milan Munich Chicago Boston Seattle Houston
Dallas Miami Atlanta Denver Washington California Texas Florida Hawaii Manhattan Brooklyn Everest Himalaya Yangtze Pacific
Atlantic Singapore Shenyang Harbin Dalian Kunming Guilin Lhasa Xinjiang Tibet Yunnan Sichuan Guangdong Zhejiang Jiangsu
Fujian Hainan Shandong Henan Hubei Hunan Hebei""".split()

NAME = """James John Robert Michael William David Richard Joseph Thomas Charles Daniel Matthew Anthony Mark Steven Andrew Kevin
Brian George Edward Jason Jeffrey Ryan Jacob Gary Eric Jonathan Stephen Larry Justin Scott Brandon Benjamin Samuel Frank
Gregory Raymond Alexander Patrick Jack Dennis Jerry Tyler Aaron Henry Peter Adam Nathan Kyle Walter Harold Mary Patricia
Jennifer Linda Elizabeth Barbara Susan Jessica Sarah Karen Nancy Lisa Margaret Betty Sandra Ashley Emily Kimberly Donna
Michelle Carol Amanda Melissa Deborah Stephanie Rebecca Laura Sharon Cynthia Kathleen Amy Angela Anna Emma Olivia Sophia
Isabella Alice Grace Lucy Chloe Hannah Victoria Smith Johnson Williams Brown Jones Garcia Miller Davis Rodriguez Martinez
Hernandez Lopez Wilson Anderson Taylor Moore Jackson Martin Lee Thompson White Harris Clark Lewis Robinson Walker Young
Allen King Wright Scott Green Baker Adams Nelson Hill Campbell Mitchell Roberts Carter Phillips Evans Turner Parker Collins
Edwards Stewart Morris Murphy Cook Rogers Einstein Newton Darwin Edison Shakespeare Obama Trump Biden Musk Zuckerberg
Bezos Jobs Gates Altman Hinton Turing Hawking Mozart Beethoven Picasso Shakespeare Napoleon Churchill Lincoln Kennedy
Washington Jefferson Roosevelt Putin Merkel Macron Zelensky Messi Ronaldo Jordan Kobe Curry LeBron""".split()

OVERRIDE_GROUPS = [TECH, COMPANY, PLACE]


def main() -> int:
    seen = {}
    for group in OVERRIDE_GROUPS:
        for w in group:
            seen.setdefault(w.lower(), (w, True))
    for w in NAME:
        seen.setdefault(w.lower(), (w, False))
    lines = [w + ("\t!" if override else "") for w, override in seen.values()]
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(lines)} proper nouns -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
