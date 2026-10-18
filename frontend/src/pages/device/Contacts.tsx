import { useState, useCallback, useMemo } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, ContactEntry } from '@/types';
import { CMD, normalizeContactList, extractList } from '@/types';
import { DevicePageHeader, EmptyState, ErrorAlert, LoadingSkeleton } from '@/components/device/shared';
import { DataActionsMenu, buildDataActions } from '@/components/device/DataActionsMenu';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Users, Search, MoreVertical, Trash2, BanIcon, UserPlus, Phone } from 'lucide-react';
import { clientsApi } from '@/services/api';
import { useToast } from '@/components/ui/use-toast';

// v4.0 extended contact entry with contactId
interface ContactEntryV4 extends ContactEntry {
  contactId?: number;
  phoneType?: number;
}

const PHONE_TYPE_LABELS: Record<number, string> = { 1: 'Home', 2: 'Mobile', 3: 'Work', 4: 'Work Fax', 5: 'Home Fax', 6: 'Pager', 7: 'Other' };

export default function ContactsPage() {
  const { clientId, online } = useOutletContext<DeviceOutletContext>();
  const { toast } = useToast();
  const [search, setSearch] = useState('');
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  // v4.0: add contact dialog
  const [showAddDialog, setShowAddDialog] = useState(false);
  const [addName,  setAddName]  = useState('');
  const [addPhone, setAddPhone] = useState('');
  const [addLoading, setAddLoading] = useState(false);

  const { data: contacts, loading, error, refresh, sendCommand, commandStatus, clearData } =
    useDeviceData<ContactEntryV4[]>({
      clientId,
      page: 'contacts',
      extractData: (d) => normalizeContactList(extractList(d.list)) as ContactEntryV4[],
      dataType: 'contacts',
      defaultValue: [],
    });

  const dataActions = buildDataActions({ data: contacts, exportPrefix: 'contacts', onClear: clearData });

  const fetchContacts = useCallback(async () => {
    await sendCommand(CMD.CONTACTS, { action: 'list' });
  }, [sendCommand]);

  const filteredContacts = useMemo(() => {
    if (!search) return contacts;
    const q = search.toLowerCase();
    return contacts.filter(
      (c) => (c.name || '').toLowerCase().includes(q) || (c.number || '').toLowerCase().includes(q),
    );
  }, [contacts, search]);

  // ── v4.0 actions ─────────────────────────────────────────────────────────

  const deleteContact = useCallback(async (c: ContactEntryV4) => {
    if (!online || !c.contactId) {
      toast({ title: 'Cannot delete', description: 'No contact ID available', variant: 'destructive' });
      return;
    }
    const key = `delete:${c.contactId}`;
    setActionLoading(key);
    try {
      await clientsApi.sendCommand(clientId, CMD.CONTACTS, {
        action: 'delete_contact',
        contactId: c.contactId,
      });
      toast({ title: 'Deleted', description: `${c.name || c.number} removed` });
      // Optimistically remove from list
      await refresh();
    } catch (e: any) {
      toast({ title: 'Delete failed', description: e?.response?.data?.error || 'Error', variant: 'destructive' });
    } finally {
      setActionLoading(null);
    }
  }, [clientId, online, refresh, toast]);

  const blockNumber = useCallback(async (c: ContactEntryV4) => {
    if (!online) return;
    const key = `block:${c.number}`;
    setActionLoading(key);
    try {
      await clientsApi.sendCommand(clientId, CMD.CONTACTS, {
        action: 'block_number',
        phoneNo: c.number,
      });
      toast({ title: 'Blocked', description: `${c.number} added to block list` });
    } catch (e: any) {
      toast({ title: 'Block failed', description: e?.response?.data?.error || 'Error', variant: 'destructive' });
    } finally {
      setActionLoading(null);
    }
  }, [clientId, online, toast]);

  const addContact = useCallback(async () => {
    if (!addName && !addPhone) return;
    setAddLoading(true);
    try {
      await clientsApi.sendCommand(clientId, CMD.CONTACTS, {
        action: 'add_contact',
        contactName: addName,
        phoneNo: addPhone,
      });
      toast({ title: 'Added', description: `${addName || addPhone} created` });
      setShowAddDialog(false);
      setAddName('');
      setAddPhone('');
      await refresh();
    } catch (e: any) {
      toast({ title: 'Add failed', description: e?.response?.data?.error || 'Error', variant: 'destructive' });
    } finally {
      setAddLoading(false);
    }
  }, [clientId, addName, addPhone, refresh, toast]);

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Contacts"
        subtitle={`${contacts.length} contacts`}
        actions={[
          { label: 'Fetch', icon: Users, onClick: fetchContacts, disabled: loading || !online },
          // v4.0: Add Contact button
          { label: 'Add', icon: UserPlus, onClick: () => setShowAddDialog(true), disabled: !online, variant: 'outline' },
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
            placeholder="Search…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-8 h-8 text-xs w-full sm:w-48"
          />
        </div>
      </div>

      {loading && !error ? (
        <LoadingSkeleton rows={8} />
      ) : filteredContacts.length === 0 ? (
        <EmptyState
          icon={Users}
          title={search ? 'No contacts match your search' : 'No contacts'}
          description={search ? 'Try a different search term' : 'Click Fetch to retrieve contacts'}
          action={!search ? { label: 'Fetch Contacts', onClick: fetchContacts, disabled: loading || !online, loading: commandStatus === 'sending' } : undefined}
        />
      ) : (
        <Card className="shadow-none overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="text-xs">Name</TableHead>
                <TableHead className="text-xs">Number</TableHead>
                <TableHead className="text-xs">Type</TableHead>
                {/* v4.0: actions column */}
                <TableHead className="text-xs w-10" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredContacts.map((c, i) => (
                <TableRow key={`contact-${c.number}-${c.name}-${i}`} className="group">
                  <TableCell className="font-medium text-xs">{c.name || '-'}</TableCell>
                  <TableCell className="font-mono text-xs">{c.number || '-'}</TableCell>
                  <TableCell className="text-xs text-muted-foreground">
                    {c.phoneType != null ? (PHONE_TYPE_LABELS[c.phoneType] || String(c.phoneType)) : (c.type || '-')}
                  </TableCell>
                  {/* v4.0: per-row action menu */}
                  <TableCell className="p-1">
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="h-6 w-6 p-0 opacity-0 group-hover:opacity-100 transition-opacity"
                          disabled={!online}
                        >
                          <MoreVertical className="h-3.5 w-3.5" />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end" className="w-36">
                        <DropdownMenuItem
                          onClick={() => blockNumber(c)}
                          disabled={!c.number || actionLoading === `block:${c.number}`}
                        >
                          <BanIcon className="h-3.5 w-3.5 mr-2 text-yellow-500" />
                          Block Number
                        </DropdownMenuItem>
                        <DropdownMenuSeparator />
                        <DropdownMenuItem
                          onClick={() => deleteContact(c)}
                          disabled={!c.contactId || actionLoading === `delete:${c.contactId}`}
                          className="text-destructive focus:text-destructive"
                        >
                          <Trash2 className="h-3.5 w-3.5 mr-2" />
                          Delete
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}

      {/* v4.0: Add Contact dialog */}
      <Dialog open={showAddDialog} onOpenChange={setShowAddDialog}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2 text-sm">
              <UserPlus className="h-4 w-4" />
              Add Contact
            </DialogTitle>
          </DialogHeader>
          <div className="space-y-3 py-2">
            <div className="space-y-1.5">
              <Label className="text-xs">Name</Label>
              <Input
                placeholder="Full name"
                value={addName}
                onChange={(e) => setAddName(e.target.value)}
                className="h-8 text-xs"
              />
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs">Phone Number</Label>
              <Input
                placeholder="+1 555 000 0000"
                value={addPhone}
                onChange={(e) => setAddPhone(e.target.value)}
                className="h-8 text-xs font-mono"
                type="tel"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setShowAddDialog(false)}>Cancel</Button>
            <Button
              size="sm"
              onClick={addContact}
              disabled={(!addName && !addPhone) || addLoading}
            >
              {addLoading ? 'Adding…' : 'Add Contact'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
