package scrape

// DefaultSources are public lists that were live and well-formed when chosen
// (September 2026). They churn; the app lets the user edit this list.
var DefaultSources = []Source{
	{URL: "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt", Kind: "socks5"},
	{URL: "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt", Kind: "http"},
	{URL: "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt", Kind: "socks5"},
	{URL: "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks4.txt", Kind: "socks4a"},
	{URL: "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt", Kind: "http"},
	{URL: "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt", Kind: "socks5"},
	{URL: "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt", Kind: "socks5"},
	{URL: "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&protocol=socks5&proxy_format=protocolipport&format=text", Kind: "socks5"},
	{URL: "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&protocol=http&proxy_format=protocolipport&format=text", Kind: "http"},
}
