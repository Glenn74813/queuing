/** Builds the STOMP broker URL from the page location: ws(s)://<host>/ws. */
export function brokerUrlFromLocation(loc: Pick<Location, 'protocol' | 'host'> = window.location): string {
  const scheme = loc.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${loc.host}/ws`;
}
