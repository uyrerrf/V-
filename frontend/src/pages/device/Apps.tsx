import { useState, useCallback, useMemo } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, AppEntry } from '@/types';
import { CMD, normalizeAppList, extractList } from '@/types';
import { DevicePageHeader, EmptyState, ErrorAlert, GridItemCard, LoadingSkeleton } from '@/components/device/shared';
import { DataActionsMenu, buildDataActions } from '@/components/device/DataActionsMenu';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Smartphone, Search, MoreVertical, Play, Square, Trash2,
  EyeOff, Eye, HardDrive, Info,
} from 'lucide-react';
import { clientsApi } from '@/services/api';
import { useToast } from '@/components/ui/use-toast';

// ── Extended app entry type (v4.0) ───────────────────────────────────────────

interface AppEntryV4 extends AppEntry {
  iconBase64?: string;
  installTime?: number;
  updateTime?: number;
}

// ── Component ────────────────────────────────────────────────────────────────

export default function AppsPage() {
  const { clientId, online } = useOutletContext<DeviceOutletContext>();
  const { toast } = useToast();
  const [showSystem, setShowSystem] = useState(false);
  const [search, setSearch] = useState('');
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  const { data: apps, loading, error, refresh, sendCommand, commandStatus, clearData } =
    useDeviceData<AppEntryV4[]>({
      clientId,
      page: 'apps',
      extractData: (d) => normalizeAppList(extractList(d.list)) as AppEntryV4[],
      dataType: 'apps',
      defaultValue: [],
    });

  const dataActions = buildDataActions({ data: apps, exportPrefix: 'apps', onClear: clearData });

  const fetchUserApps = useCallback(async () => {
    await sendCommand(CMD.APPS, { sys: false });
  }, [sendCommand]);

  const fetchAllApps = useCallback(async () => {
    await sendCommand(CMD.APPS, { sys: true });
  }, [sendCommand]);

  const filteredApps = useMemo(() => {
    const base = showSystem ? apps : apps.filter((a) => !a.isSystem);
    if (!search) return base;
    const q = search.toLowerCase();
    return base.filter(
      (a) => a.name.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q),
    );
  }, [apps, showSystem, search]);

  // ── v4.0 app actions ───────────────────────────────────────────────────────

  const doAction = useCallback(async (action: string, pkg: string, label: string) => {
    if (!online) return;
    setActionLoading(`${action}:${pkg}`);
    try {
      await clientsApi.sendCommand(clientId, CMD.APPS, { action, packageName: pkg });
      toast({ title: label, description: `Sent to ${pkg}` });
    } catch (e: any) {
      toast({ title: 'Failed', description: e?.response?.data?.error || label + ' failed', variant: 'destructive' });
    } finally {
      setActionLoading(null);
    }
  }, [clientId, online, toast]);

  if (loading && !error) return <LoadingSkeleton rows={9} variant="cards" />;

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Installed Apps"
        subtitle={`${filteredApps.length} apps`}
        actions={[
          { label: 'User', icon: Smartphone, onClick: fetchUserApps, disabled: loading || !online },
          { label: 'All',  onClick: fetchAllApps,  disabled: loading || !online, variant: 'outline' },
        ]}
        moreActions={<DataActionsMenu actions={dataActions} disabled={loading} />}
        refresh={refresh}
        loading={loading}
        commandStatus={commandStatus}
      />

      {error && <ErrorAlert message={error} onRetry={refresh} />}

      <div className="flex items-center gap-2">
        <div className="relative flex-1 sm:flex-initial">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
          <Input
            placeholder="Search apps…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-8 h-8 text-xs w-full sm:w-56"
          />
        </div>
        <Badge
          variant={showSystem ? 'secondary' : 'outline'}
          className="cursor-pointer select-none text-xs px-2.5 py-1"
          onClick={() => setShowSystem(!showSystem)}
        >
          {showSystem ? 'All Apps' : 'User Only'}
        </Badge>
      </div>

      {filteredApps.length === 0 ? (
        <EmptyState
          icon={Smartphone}
          title={search ? 'No apps match your search' : 'No apps data'}
          description={search ? 'Try a different search' : 'Click User or All to fetch app list'}
          action={!search ? { label: 'Fetch Apps', onClick: fetchUserApps, disabled: loading || !online, loading: commandStatus === 'sending' } : undefined}
        />
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
          {filteredApps.map((app, i) => (
            <AppCard
              key={`app-${app.packageName}-${i}`}
              app={app}
              online={online}
              actionLoading={actionLoading}
              onAction={doAction}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ── AppCard sub-component ────────────────────────────────────────────────────

function AppCard({
  app, online, actionLoading, onAction,
}: {
  app: AppEntryV4;
  online: boolean;
  actionLoading: string | null;
  onAction: (action: string, pkg: string, label: string) => void;
}) {
  const busy = (action: string) => actionLoading === `${action}:${app.packageName}`;

  return (
    <div className="flex items-center gap-3 rounded-lg border bg-card p-2.5 shadow-none hover:bg-accent/30 transition-colors group">
      {/* App icon */}
      {app.iconBase64 ? (
        <img
          src={`data:image/png;base64,${app.iconBase64}`}
          alt={app.name}
          className="h-10 w-10 rounded-lg object-contain shrink-0 bg-muted"
          onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
        />
      ) : (
        <div className="h-10 w-10 rounded-lg bg-muted flex items-center justify-center shrink-0">
          <Smartphone className="h-5 w-5 text-muted-foreground" />
        </div>
      )}

      {/* Info */}
      <div className="flex-1 min-w-0">
        <p className="text-xs font-medium truncate">{app.name || 'Unknown'}</p>
        <p className="text-[10px] text-muted-foreground truncate font-mono">{app.packageName}</p>
        <div className="flex items-center gap-1 mt-0.5 flex-wrap">
          {app.isSystem && (
            <Badge variant="outline" className="text-[9px] py-0 px-1">System</Badge>
          )}
          {!app.enabled && (
            <Badge variant="destructive" className="text-[9px] py-0 px-1">Disabled</Badge>
          )}
          {app.versionName && (
            <span className="text-[9px] text-muted-foreground">v{app.versionName}</span>
          )}
        </div>
      </div>

      {/* Actions dropdown */}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 w-7 p-0 opacity-0 group-hover:opacity-100 transition-opacity"
            disabled={!online}
          >
            <MoreVertical className="h-3.5 w-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-40">
          <DropdownMenuItem
            onClick={() => onAction('open_app', app.packageName, 'Open')}
            disabled={busy('open_app')}
          >
            <Play className="h-3.5 w-3.5 mr-2 text-green-500" />
            Open
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => onAction('force_stop', app.packageName, 'Force Stop')}
            disabled={busy('force_stop')}
          >
            <Square className="h-3.5 w-3.5 mr-2 text-orange-500" />
            Force Stop
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => onAction('clear_cache', app.packageName, 'Clear Cache')}
            disabled={busy('clear_cache')}
          >
            <HardDrive className="h-3.5 w-3.5 mr-2 text-blue-500" />
            Clear Cache
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          {app.enabled ? (
            <DropdownMenuItem
              onClick={() => onAction('disable_app', app.packageName, 'Disable')}
              disabled={busy('disable_app') || !!app.isSystem}
            >
              <EyeOff className="h-3.5 w-3.5 mr-2 text-yellow-500" />
              Disable
            </DropdownMenuItem>
          ) : (
            <DropdownMenuItem
              onClick={() => onAction('enable_app', app.packageName, 'Enable')}
              disabled={busy('enable_app')}
            >
              <Eye className="h-3.5 w-3.5 mr-2 text-green-500" />
              Enable
            </DropdownMenuItem>
          )}
          <DropdownMenuItem
            onClick={() => onAction('app_info', app.packageName, 'App Info')}
            disabled={busy('app_info')}
          >
            <Info className="h-3.5 w-3.5 mr-2" />
            App Info
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => onAction('uninstall', app.packageName, 'Uninstall')}
            disabled={busy('uninstall') || !!app.isSystem}
            className="text-destructive focus:text-destructive"
          >
            <Trash2 className="h-3.5 w-3.5 mr-2" />
            Uninstall
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
