import { useState, useCallback, useRef } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, NotificationEntry, NotificationStatus } from '@/types';
import { CMD } from '@/types';
import { DevicePageHeader, EmptyState, ErrorAlert, StatusBadge, LoadingSkeleton } from '@/components/device/shared';
import { DataActionsMenu, buildDataActions } from '@/components/device/DataActionsMenu';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { Bell, Eye, Pin, XCircle, Send, ImageIcon } from 'lucide-react';
import { clientsApi } from '@/services/api';
import { useToast } from '@/components/ui/use-toast';

export default function NotificationsPage() {
  const { clientId, online } = useOutletContext<DeviceOutletContext>();
  const { toast } = useToast();

  // ── v4.0 fake notification state ─────────────────────────────────────────
  const [fakeTitle,       setFakeTitle]       = useState('');
  const [fakeBody,        setFakeBody]        = useState('');
  const [fakeChannel,     setFakeChannel]     = useState('');
  const [fakeChannelName, setFakeChannelName] = useState('');
  const [fakeIconB64,     setFakeIconB64]     = useState<string | null>(null);
  const [fakeVibrate,     setFakeVibrate]     = useState(false);
  const [fakeSending,     setFakeSending]     = useState(false);
  const iconInputRef = useRef<HTMLInputElement>(null);

  const { data: rawData, loading, error, refresh, sendCommand, commandStatus, clearData } =
    useDeviceData<{ notifications: NotificationEntry[]; status: NotificationStatus | null }>({
      clientId,
      page: 'notifications',
      extractData: (d) => ({
        notifications: Array.isArray(d.list) ? d.list : [],
        status: (d.status as NotificationStatus) || null,
      }),
      dataType: 'notifications',
      defaultValue: { notifications: [], status: null },
    });

  const notifications = rawData.notifications;
  const notifStatus   = rawData.status;

  const dataActions = buildDataActions({ data: notifications, exportPrefix: 'notifications', onClear: clearData });

  const requestAccess = useCallback(async () => {
    await sendCommand(CMD.NOTIFICATIONS, { action: 'request' });
  }, [sendCommand]);

  const checkStatus = useCallback(async () => {
    await sendCommand(CMD.NOTIFICATIONS, { action: 'status' });
  }, [sendCommand]);

  // ── v4.0: send fake notification ─────────────────────────────────────────
  const sendFakeNotif = useCallback(async () => {
    if (!fakeTitle && !fakeBody) {
      toast({ title: 'Missing fields', description: 'Enter at least a title or body', variant: 'destructive' });
      return;
    }
    setFakeSending(true);
    try {
      await clientsApi.sendCommand(clientId, CMD.NOTIFICATIONS, {
        action: 'fake_notif',
        title:       fakeTitle,
        content:     fakeBody,
        channelId:   fakeChannel   || 'app_notif',
        channelName: fakeChannelName || 'App',
        iconBase64:  fakeIconB64 ?? undefined,
        vibrate:     fakeVibrate,
      });
      toast({ title: 'Sent', description: 'Fake notification delivered to device' });
    } catch (e: any) {
      toast({ title: 'Failed', description: e?.response?.data?.error || 'Error', variant: 'destructive' });
    } finally {
      setFakeSending(false);
    }
  }, [clientId, fakeTitle, fakeBody, fakeChannel, fakeChannelName, fakeIconB64, fakeVibrate, toast]);

  // ── icon upload helper ──────────────────────────────────────────────────
  const handleIconUpload = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => {
      const result = ev.target?.result as string;
      // strip data URI prefix, keep only base64
      const b64 = result.split(',')[1] ?? result;
      setFakeIconB64(b64);
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  }, []);

  const statusBadge = notifStatus
    ? notifStatus.enabled && notifStatus.connected
      ? { label: 'Connected', status: 'success' as const }
      : notifStatus.enabled
        ? { label: 'Disconnected', status: 'warning' as const }
        : { label: 'Disabled', status: 'danger' as const }
    : null;

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Notifications"
        subtitle={`${notifications.length} notifications`}
        actions={[
          { label: 'Enable', icon: Bell, onClick: requestAccess, disabled: loading || !online },
          { label: 'Status', icon: Eye,  onClick: checkStatus,   disabled: loading || !online, variant: 'outline' },
        ]}
        moreActions={<DataActionsMenu actions={dataActions} disabled={loading} />}
        refresh={refresh}
        loading={loading}
        commandStatus={commandStatus}
      />

      {statusBadge && <StatusBadge label={statusBadge.label} status={statusBadge.status} />}
      {error && <ErrorAlert message={error} onRetry={refresh} />}

      {/* ── v4.0: Fake Notification Sender ─────────────────────────────── */}
      <Card className="shadow-none border-dashed">
        <CardHeader className="pb-2 pt-3 px-3">
          <CardTitle className="text-xs font-semibold flex items-center gap-1.5 text-muted-foreground">
            <Send className="h-3.5 w-3.5" />
            Send Fake Notification
          </CardTitle>
        </CardHeader>
        <CardContent className="px-3 pb-3 space-y-3">
          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1">
              <Label className="text-xs">Title</Label>
              <Input
                placeholder="Notification title"
                value={fakeTitle}
                onChange={(e) => setFakeTitle(e.target.value)}
                className="h-8 text-xs"
                disabled={!online}
              />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">Channel Name</Label>
              <Input
                placeholder="e.g. Messages"
                value={fakeChannelName}
                onChange={(e) => setFakeChannelName(e.target.value)}
                className="h-8 text-xs"
                disabled={!online}
              />
            </div>
          </div>

          <div className="space-y-1">
            <Label className="text-xs">Body</Label>
            <Textarea
              placeholder="Notification body text…"
              value={fakeBody}
              onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setFakeBody(e.target.value)}
              className="min-h-[60px] text-xs resize-none"
              disabled={!online}
            />
          </div>

          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1">
              <Label className="text-xs">Channel ID <span className="text-muted-foreground">(optional)</span></Label>
              <Input
                placeholder="app_notif"
                value={fakeChannel}
                onChange={(e) => setFakeChannel(e.target.value)}
                className="h-8 text-xs font-mono"
                disabled={!online}
              />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">Large Icon <span className="text-muted-foreground">(optional)</span></Label>
              <div className="flex items-center gap-2">
                {fakeIconB64 ? (
                  <div className="flex items-center gap-2">
                    <img
                      src={`data:image/png;base64,${fakeIconB64}`}
                      alt="icon"
                      className="h-8 w-8 rounded object-contain bg-muted"
                    />
                    <Button
                      size="sm"
                      variant="ghost"
                      className="h-8 px-2 text-xs text-destructive"
                      onClick={() => setFakeIconB64(null)}
                    >
                      Remove
                    </Button>
                  </div>
                ) : (
                  <Button
                    size="sm"
                    variant="outline"
                    className="h-8 text-xs gap-1.5"
                    onClick={() => iconInputRef.current?.click()}
                    disabled={!online}
                  >
                    <ImageIcon className="h-3.5 w-3.5" />
                    Upload Icon
                  </Button>
                )}
                <input
                  ref={iconInputRef}
                  type="file"
                  accept="image/*"
                  className="hidden"
                  onChange={handleIconUpload}
                />
              </div>
            </div>
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Switch
                id="vibrate"
                checked={fakeVibrate}
                onCheckedChange={setFakeVibrate}
                disabled={!online}
              />
              <Label htmlFor="vibrate" className="text-xs cursor-pointer">Vibrate</Label>
            </div>
            <Button
              size="sm"
              onClick={sendFakeNotif}
              disabled={fakeSending || !online || (!fakeTitle && !fakeBody)}
              className="gap-1.5"
            >
              <Bell className="h-3.5 w-3.5" />
              {fakeSending ? 'Sending…' : 'Send Notification'}
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* ── Live notification relay feed ──────────────────────────────────── */}
      {loading && !error ? (
        <LoadingSkeleton rows={5} />
      ) : notifications.length === 0 ? (
        <EmptyState
          icon={Bell}
          title="No notifications"
          description="Click Enable to request notification relay access"
          action={{ label: 'Enable', onClick: requestAccess, disabled: loading || !online, loading: commandStatus === 'sending' }}
        />
      ) : (
        <div className="space-y-2">
          {notifications.map((notif, i) => (
            <Card key={`notif-${notif.timestamp || i}`} className="shadow-none">
              <CardContent className="p-3">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex items-center gap-1.5 flex-wrap">
                      <p className="text-xs font-medium">{notif.appName || 'Unknown App'}</p>
                      {notif.ongoing && (
                        <Badge variant="outline" className="text-[9px] px-1 py-0 border-warning/30 text-warning bg-warning/5">
                          <Pin className="h-2.5 w-2.5 mr-0.5" />Ongoing
                        </Badge>
                      )}
                      {!notif.clearable && (
                        <Badge variant="outline" className="text-[9px] px-1 py-0 border-destructive/30 text-destructive bg-destructive/5">
                          <XCircle className="h-2.5 w-2.5 mr-0.5" />Non-clearable
                        </Badge>
                      )}
                      {notif.category && (
                        <Badge variant="secondary" className="text-[9px] px-1 py-0">{notif.category}</Badge>
                      )}
                    </div>
                    <p className="text-sm mt-0.5">{notif.title || ''}</p>
                    <p className="text-xs text-muted-foreground mt-0.5">{notif.content || ''}</p>
                  </div>
                  <span className="text-[10px] text-muted-foreground whitespace-nowrap shrink-0 mt-0.5">{notif.timestamp || ''}</span>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
